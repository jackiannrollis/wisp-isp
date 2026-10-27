package com.corebalt.isp.subscriptions.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.corebalt.isp.network.application.ProbeSetup;
import com.corebalt.isp.subscriptions.api.ProbeLines;
import com.corebalt.isp.subscriptions.application.LifecycleService;
import com.corebalt.isp.tenancy.api.ProbeTenant;
import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaFieldAccess;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * S12: a line leaves PENDING for ACTIVE only by RENEWAL, IMPORTED or PROBE, each through one entry point.
 * Checked on the compiled code (ArchUnit: who may call what, who may write the state), by trying every
 * other mutator of a line on a PENDING one, and by scanning the sources for SQL that would bypass the
 * entity. The database refuses the rest (ActivationCauseIT).
 */
class ActivationEntryPointsTest {

    private static final Instant NOW = Instant.parse("2026-10-27T09:00:00Z");

    private static JavaClasses classes;

    @BeforeAll
    static void importProductionCode() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.corebalt.isp");
    }

    @Test
    void S12_onlyTheThreeEntryPointsActivateALine() {
        assertThat(callers(Subscription.class, "activate")).containsExactlyInAnyOrder(
                "Subscription.renew", "Subscription.activateByImport", "Subscription.activateForProbe");
    }

    @Test
    void S12_eachEntryPointHasOneCaller_RenewalFromTheLedger_ImportFromTheImport_ProbeFromTheProbeSetup() {
        assertThat(callers(Subscription.class, "renew")).containsExactly("RenewalService.renew");
        assertThat(callers(Subscription.class, "activateByImport")).containsExactly("LifecycleService.activateByImport");
        assertThat(callerClasses(LifecycleService.class, "activateByImport")).containsExactly("CustomerImportService");
        assertThat(callers(Subscription.class, "activateForProbe")).containsExactly("LifecycleService.activateForProbe");
        assertThat(callerClasses(LifecycleService.class, "activateForProbe")).containsExactly("ProbeLineService");
        assertThat(callerClasses(ProbeLines.class, "open")).containsExactly(ProbeSetup.class.getSimpleName());
    }

    @Test
    void S12_onlyTheLineWritesItsStateAndActivationCause() {
        final JavaClass line = classes.get(Subscription.class);
        for (final String field : List.of("state", "activationCause")) {
            final Set<String> writers = line.getField(field).getAccessesToSelf().stream()
                    .filter(a -> a.getAccessType() == JavaFieldAccess.AccessType.SET)
                    .map(a -> a.getOriginOwner().getName())
                    .collect(Collectors.toSet());
            assertThat(writers).as(field).containsExactly(Subscription.class.getName());
        }
        final Set<String> causeWriters = line.getField("activationCause").getAccessesToSelf().stream()
                .filter(a -> a.getAccessType() == JavaFieldAccess.AccessType.SET)
                .map(a -> a.getOrigin().getName())
                .collect(Collectors.toSet());
        assertThat(causeWriters).containsExactly("activate");
    }

    /** Every other way to change a line, on a PENDING one: it throws or the line is still not ACTIVE. */
    @Test
    void S12_noOtherMutatorTurnsAPendingLineActive() {
        final List<Consumer<Subscription>> others = List.of(
                l -> l.pause(NOW.plus(Duration.ofDays(3)), NOW, 30),
                l -> l.resume(NOW),
                l -> l.suspend(NOW),
                l -> l.liftSuspension(NOW),
                l -> l.terminate(NOW),
                l -> l.cancelBeforeActivation(NOW),
                l -> l.changePackageNow(UUID.randomUUID(), NOW),
                l -> l.changePackageAtRenewal(UUID.randomUUID(), NOW),
                l -> l.correctKeptUsername("old.user", NOW),
                Subscription::requireRenewable);
        for (final Consumer<Subscription> change : others) {
            final Subscription line = pending(UUID.randomUUID());
            catchThrowable(() -> change.accept(line));
            assertThat(line.getState()).isNotEqualTo(SubscriptionState.ACTIVE);
            assertThat(line.getActivationCause()).isNull();
        }
    }

    /** A new public method on the line must be classified above (another mutator) or here (an entry point or a read). */
    @Test
    void S12_everyPublicMethodOfALineIsAccountedFor() {
        final Set<String> known = Set.of(
                // the three entry points (S12)
                "renew", "activateByImport", "activateForProbe",
                // the other mutators, tried on a PENDING line above
                "pause", "resume", "suspend", "liftSuspension", "terminate", "cancelBeforeActivation", "changePackageNow",
                "changePackageAtRenewal", "correctKeptUsername", "requireRenewable",
                // reads
                "open", "facts", "packageForNextRenewal", "graceModeOr");
        final Set<String> methods = Arrays.stream(Subscription.class.getDeclaredMethods())
                .filter(m -> Modifier.isPublic(m.getModifiers()))
                .map(Method::getName)
                .filter(n -> !n.startsWith("get") && !n.startsWith("is"))
                .collect(Collectors.toSet());
        assertThat(known).containsAll(methods);
    }

    @Test
    void S12_theEntryPointsActivateWithTheirCause() {
        final Subscription renewed = pending(UUID.randomUUID());
        renewed.renew(Duration.ofDays(30), 0, GraceMode.FULL_SPEED, UUID.randomUUID(), NOW);
        assertThat(renewed.getActivationCause()).isEqualTo(ActivationCause.RENEWAL);

        final Subscription imported = pending(UUID.randomUUID());
        imported.activateByImport(NOW.plus(Duration.ofDays(10)), Duration.ofDays(30), 0, GraceMode.FULL_SPEED, NOW);
        assertThat(imported.getActivationCause()).isEqualTo(ActivationCause.IMPORTED);

        final Subscription probe = pending(ProbeTenant.ID);
        probe.activateForProbe(Instant.parse("2099-12-31T00:00:00Z"), NOW);
        assertThat(probe.getActivationCause()).isEqualTo(ActivationCause.PROBE);

        final Subscription notProbe = pending(UUID.randomUUID());
        assertThat(catchThrowable(() -> notProbe.activateForProbe(Instant.parse("2099-12-31T00:00:00Z"), NOW)))
                .isInstanceOf(LifecycleViolation.class).hasMessageContaining("S12");
        assertThat(notProbe.getState()).isEqualTo(SubscriptionState.PENDING);
    }

    /** No SQL in the application moves a line's state or sets its cause behind the entity's back. */
    @Test
    void S12_noSqlInTheApplicationSetsAStateOrACause() throws IOException {
        final Pattern bypass = Pattern.compile("(?i)(update\\s+subscriptions_subscription[^;\"]*set[^;\"]*\\b(state|activation_cause)\\s*=)"
                + "|(insert\\s+into\\s+subscriptions_subscription)");
        try (Stream<Path> sources = Files.walk(Path.of("src/main/java"))) {
            final List<String> offenders = sources.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> {
                        try {
                            return bypass.matcher(Files.readString(p).replaceAll("\\s+", " ")).find();
                        } catch (IOException e) {
                            throw new IllegalStateException(e);
                        }
                    })
                    .map(Path::toString)
                    .toList();
            assertThat(offenders).isEmpty();
        }
    }

    private static Subscription pending(final UUID tenantId) {
        return Subscription.open(UUID.randomUUID(), tenantId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "CB000001", false, NOW);
    }

    /** The classes whose code calls {@code method}, lambdas included (they may be compiled to synthetic methods). */
    private static Set<String> callerClasses(final Class<?> owner, final String method) {
        return classes.get(owner).getMethods().stream()
                .filter(m -> m.getName().equals(method))
                .flatMap(m -> m.getAccessesToSelf().stream())
                .map(a -> a.getOriginOwner().getSimpleName())
                .collect(Collectors.toSet());
    }

    private static Set<String> callers(final Class<?> owner, final String method) {
        return classes.get(owner).getMethods().stream()
                .filter(m -> m.getName().equals(method))
                .flatMap(m -> m.getAccessesToSelf().stream())
                .map(JavaAccess::getOrigin)
                .map(o -> o.getOwner().getSimpleName() + "." + o.getName())
                .collect(Collectors.toSet());
    }
}
