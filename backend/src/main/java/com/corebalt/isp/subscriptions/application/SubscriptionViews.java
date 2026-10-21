package com.corebalt.isp.subscriptions.application;

import com.corebalt.isp.packages.api.PackageCatalog;
import com.corebalt.isp.packages.api.PackageCatalog.PackageLimits;
import com.corebalt.isp.subscriptions.api.LineUsage;
import com.corebalt.isp.subscriptions.api.SubscriptionDto;
import com.corebalt.isp.subscriptions.domain.AccessPolicy;
import com.corebalt.isp.subscriptions.domain.AccessProfile;
import com.corebalt.isp.subscriptions.domain.AccessReason;
import com.corebalt.isp.subscriptions.domain.DisplayStatus;
import com.corebalt.isp.subscriptions.domain.FairUse;
import com.corebalt.isp.subscriptions.domain.GraceMode;
import com.corebalt.isp.subscriptions.domain.Subscription;
import com.corebalt.isp.tenancy.api.TenantProfile;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Lines as DTOs, with the access profile and display status derived now (S1), from the stored dates
 * and the usage of the line's current cycle against its package's limits (docs/09 step 8b).
 */
@Component
class SubscriptionViews {

    private final TenantProfile tenantProfile;
    private final LineUsage usage;
    private final PackageCatalog packages;
    private final Clock clock;

    SubscriptionViews(final TenantProfile tenantProfile, final LineUsage usage, final PackageCatalog packages,
                      final Clock clock) {
        this.tenantProfile = tenantProfile;
        this.usage = usage;
        this.packages = packages;
        this.clock = clock;
    }

    /** A mapper for these lines, bound to the tenant's grace mode, one instant and their usage, read at once. */
    Mapper mapper(final Collection<Subscription> lines) {
        final Map<UUID, Instant> cycles = new HashMap<>();
        lines.stream().filter(l -> l.getCycleStartedAt() != null).forEach(l -> cycles.put(l.getId(), l.getCycleStartedAt()));
        final Map<UUID, Long> octets = cycles.isEmpty() ? Map.of() : usage.cycleOctets(cycles);
        return new Mapper(GraceMode.valueOf(tenantProfile.currentPolicy().graceMode()), clock.instant(), octets,
                new HashMap<>());
    }

    SubscriptionDto toDto(final Subscription line) {
        return mapper(List.of(line)).toDto(line);
    }

    /** The current tenant's grace mode, as stored (FULL_SPEED or THROTTLED). */
    String graceMode() {
        return tenantProfile.currentPolicy().graceMode();
    }

    /**
     * The profile at {@code at}, with {@code expiresAt} in place of the stored expiry when not null,
     * judged with the line's current cycle usage.
     */
    AccessProfile profile(final Subscription line, final Instant at, final Instant expiresAt) {
        final Mapper mapper = mapper(List.of(line));
        final AccessPolicy.Facts facts = mapper.facts(line);
        return AccessPolicy.evaluate(expiresAt == null ? facts : facts.withExpiresAt(expiresAt), mapper.graceMode(), at)
                .profile();
    }

    /** The profile at {@code at} without the limit just crossed: see SubscriptionDirectory#accessProfileBeforeLimit. */
    AccessProfile profileBeforeLimit(final Subscription line, final Instant at, final boolean dataCap) {
        final Mapper mapper = mapper(List.of(line));
        final AccessPolicy.Facts facts = mapper.facts(line);
        final FairUse.Limits before = new FairUse.Limits(dataCap && facts.fairUseExceeded(), false);
        return AccessPolicy.evaluate(facts.withUsage(before), mapper.graceMode(), at).profile();
    }

    final class Mapper {

        private final GraceMode graceMode;
        private final Instant now;
        private final Map<UUID, Long> octets;
        private final Map<UUID, PackageLimits> limits;

        private Mapper(final GraceMode graceMode, final Instant now, final Map<UUID, Long> octets,
                       final Map<UUID, PackageLimits> limits) {
            this.graceMode = graceMode;
            this.now = now;
            this.octets = octets;
            this.limits = limits;
        }

        GraceMode graceMode() {
            return graceMode;
        }

        /** The one instant every line of this mapper is judged at. */
        Instant now() {
            return now;
        }

        AccessPolicy.Facts facts(final Subscription line) {
            return line.facts().withUsage(limitsReached(line));
        }

        SubscriptionDto toDto(final Subscription line) {
            final AccessPolicy.Facts facts = facts(line);
            final AccessPolicy.Decision decision = AccessPolicy.evaluate(facts, graceMode, now);
            final PackageLimits packageLimits = limitsOf(line);
            return new SubscriptionDto(
                    line.getId(),
                    line.getCustomerId(),
                    line.getPackageId(),
                    line.getRouterId(),
                    line.getPppoeUsername(),
                    line.getState().name(),
                    decision.profile().name(),
                    DisplayStatus.of(facts, decision, now).name(),
                    reason(facts, decision),
                    line.getExpiresAt(),
                    line.getGraceDays(),
                    line.getPausedAt(),
                    line.getPauseUntil(),
                    line.getActivatedAt(),
                    line.getTerminatedAt(),
                    line.getCreatedAt(),
                    line.getUpdatedAt(),
                    new SubscriptionDto.CycleUsage(line.getCycleStartedAt(), usedBytes(line),
                            packageLimits.fairUseBytes(), packageLimits.dataCapBytes(), facts.fairUseExceeded(),
                            facts.dataCapReached()));
        }

        private String reason(final AccessPolicy.Facts facts, final AccessPolicy.Decision decision) {
            final AccessReason reason = AccessReason.of(facts, decision, now);
            return reason == null ? null : reason.name();
        }

        private FairUse.Limits limitsReached(final Subscription line) {
            final PackageLimits packageLimits = limitsOf(line);
            return FairUse.limits(packageLimits.fairUseBytes(), packageLimits.dataCapBytes(), usedBytes(line));
        }

        /** Zero while the cycle start is unknown: such a line is not metered. */
        private long usedBytes(final Subscription line) {
            if (line.getCycleStartedAt() == null) {
                return 0;
            }
            return octets.containsKey(line.getId()) ? octets.get(line.getId()) : 0;
        }

        private PackageLimits limitsOf(final Subscription line) {
            return limits.computeIfAbsent(line.getPackageId(),
                    id -> packages.limits(id).orElse(PackageLimits.NONE));
        }
    }
}
