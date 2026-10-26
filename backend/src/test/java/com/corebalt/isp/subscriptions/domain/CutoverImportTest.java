package com.corebalt.isp.subscriptions.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The cutover import's rules on the line itself (S11, T2, ADR 0014) and its date column. */
class CutoverImportTest {

    private static final Instant NOW = Instant.parse("2026-10-26T10:00:00Z");
    private static final Duration MONTH = Duration.ofDays(30);
    private static final ZoneId NAIROBI = ZoneId.of("Africa/Nairobi");

    @Test
    void S11_importActivatesWithThePaidExpiryAndACycleOnePeriodBefore() {
        final Subscription line = pending("old-name", true);
        final Instant paidUntil = NOW.plus(Duration.ofDays(12));
        final Subscription.Transition transition = line.activateByImport(paidUntil, MONTH, 2, GraceMode.THROTTLED, NOW);
        assertThat(transition).isEqualTo(new Subscription.Transition(SubscriptionState.PENDING, SubscriptionState.ACTIVE, false));
        assertThat(line.getState()).isEqualTo(SubscriptionState.ACTIVE);
        assertThat(line.getExpiresAt()).isEqualTo(paidUntil);
        assertThat(line.getActivatedAt()).isEqualTo(NOW);
        assertThat(line.getCycleStartedAt()).isEqualTo(paidUntil.minus(MONTH));
        assertThat(line.getGraceDays()).isEqualTo(2);
        assertThat(line.graceModeOr(GraceMode.FULL_SPEED)).isEqualTo(GraceMode.THROTTLED);
    }

    @Test
    void S11_aPastExpiryIsAllowedTheCustomerLandsOnThePayPage() {
        final Subscription line = pending("old-name", true);
        line.activateByImport(NOW.minus(Duration.ofDays(3)), MONTH, 0, GraceMode.FULL_SPEED, NOW);
        assertThat(AccessPolicy.evaluate(line.facts(), GraceMode.FULL_SPEED, NOW).profile()).isEqualTo(AccessProfile.RESTRICTED);
    }

    @Test
    void S11_atMostOnePeriodAheadAndOnlyFromPending() {
        final Subscription line = pending("old-name", true);
        assertThatThrownBy(() -> line.activateByImport(NOW.plus(MONTH).plusSeconds(1), MONTH, 0, GraceMode.FULL_SPEED, NOW))
                .isInstanceOf(LifecycleViolation.class).hasMessageContaining("S11");
        line.activateByImport(NOW.plus(MONTH), MONTH, 0, GraceMode.FULL_SPEED, NOW);
        assertThatThrownBy(() -> line.activateByImport(NOW.plus(Duration.ofDays(1)), MONTH, 0, GraceMode.FULL_SPEED, NOW))
                .isInstanceOf(LifecycleViolation.class).hasMessageContaining("PENDING");
    }

    @Test
    void T2_onlyAKeptUsernameCanBeCorrected() {
        final Subscription kept = pending("jhon.doe", true);
        kept.correctKeptUsername("john.doe", NOW);
        assertThat(kept.getPppoeUsername()).isEqualTo("john.doe");
        final Subscription generated = pending("CB000001", false);
        assertThatThrownBy(() -> generated.correctKeptUsername("other", NOW)).isInstanceOf(LifecycleViolation.class)
                .hasMessageContaining("T2");
    }

    @Test
    void T2_aKeptNameIsInTheSafeShapeAndNeverInTheGeneratedOne() {
        assertThat(PppoeUsernames.keptProblem("old.user@isp")).isEmpty();
        assertThat(PppoeUsernames.keptProblem("a".repeat(64))).isEmpty();
        assertThat(PppoeUsernames.keptProblem("a".repeat(65))).isPresent();
        assertThat(PppoeUsernames.keptProblem("with space")).isPresent();
        assertThat(PppoeUsernames.keptProblem("colon:no")).isPresent();
        assertThat(PppoeUsernames.keptProblem("")).isPresent();
        assertThat(PppoeUsernames.keptProblem("CB001238")).hasValueSatisfying(why -> assertThat(why).contains("T2"));
        assertThat(PppoeUsernames.keptProblem("ACME000001-2")).hasValueSatisfying(why -> assertThat(why).contains("T2"));
        assertThat(PppoeUsernames.keptProblem("cb001238")).as("case differs: not the generated shape").isEmpty();
    }

    @Test
    void S11_aDateAloneMeansTheChosenTimeOfDayInTheTenantsZone() {
        assertThat(PaidUntil.parse("2026-11-15", NAIROBI, LocalTime.NOON))
                .contains(Instant.parse("2026-11-15T09:00:00Z"));
        assertThat(PaidUntil.parse(" 2026-11-15 ", NAIROBI, LocalTime.of(18, 30)))
                .contains(Instant.parse("2026-11-15T15:30:00Z"));
        assertThat(PaidUntil.parse("2026-11-15T08:00:00", NAIROBI, LocalTime.NOON))
                .as("a local date-time is read in the tenant's zone").contains(Instant.parse("2026-11-15T05:00:00Z"));
        assertThat(PaidUntil.parse("2026-11-15T08:00:00Z", NAIROBI, LocalTime.NOON)).contains(Instant.parse("2026-11-15T08:00:00Z"));
        assertThat(PaidUntil.parse("2026-11-15T08:00:00+03:00", NAIROBI, LocalTime.NOON)).contains(Instant.parse("2026-11-15T05:00:00Z"));
        assertThat(PaidUntil.parse("15/11/2026", NAIROBI, LocalTime.NOON)).as("never guessed").isEmpty();
        assertThat(PaidUntil.parse("2026-13-01", NAIROBI, LocalTime.NOON)).isEmpty();
        assertThat(PaidUntil.parse("", NAIROBI, LocalTime.NOON)).isEmpty();
    }

    private static Subscription pending(final String username, final boolean kept) {
        return Subscription.open(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), username, kept, NOW.minus(Duration.ofDays(1)));
    }
}
