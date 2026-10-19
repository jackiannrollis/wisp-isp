package com.corebalt.isp.subscriptions.application;

import com.corebalt.isp.subscriptions.api.SubscriptionDto;
import com.corebalt.isp.subscriptions.domain.AccessPolicy;
import com.corebalt.isp.subscriptions.domain.AccessProfile;
import com.corebalt.isp.subscriptions.domain.DisplayStatus;
import com.corebalt.isp.subscriptions.domain.GraceMode;
import com.corebalt.isp.subscriptions.domain.Subscription;
import com.corebalt.isp.tenancy.api.TenantProfile;
import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Component;

/** Lines as DTOs, with the access profile and display status derived now (S1). */
@Component
class SubscriptionViews {

    private final TenantProfile tenantProfile;
    private final Clock clock;

    SubscriptionViews(final TenantProfile tenantProfile, final Clock clock) {
        this.tenantProfile = tenantProfile;
        this.clock = clock;
    }

    /** A mapper bound to the current tenant's grace mode and one instant, for a whole list. */
    Mapper mapper() {
        return new Mapper(GraceMode.valueOf(tenantProfile.currentPolicy().graceMode()), clock.instant());
    }

    SubscriptionDto toDto(final Subscription line) {
        return mapper().toDto(line);
    }

    /** The current tenant's grace mode, as stored (FULL_SPEED or THROTTLED). */
    String graceMode() {
        return tenantProfile.currentPolicy().graceMode();
    }

    /** The profile at {@code at}, with {@code expiresAt} in place of the stored expiry when not null. */
    AccessProfile profile(final Subscription line, final Instant at, final Instant expiresAt) {
        final AccessPolicy.Facts stored = line.facts();
        final AccessPolicy.Facts facts = expiresAt == null ? stored : new AccessPolicy.Facts(
                stored.state(), expiresAt, stored.graceDays(), stored.pausedAt(), stored.pauseUntil());
        return AccessPolicy.evaluate(facts, GraceMode.valueOf(tenantProfile.currentPolicy().graceMode()), at).profile();
    }

    record Mapper(GraceMode graceMode, Instant now) {

        SubscriptionDto toDto(final Subscription line) {
            final AccessPolicy.Facts facts = line.facts();
            final AccessPolicy.Decision decision = AccessPolicy.evaluate(facts, graceMode, now);
            return new SubscriptionDto(
                    line.getId(),
                    line.getCustomerId(),
                    line.getPackageId(),
                    line.getRouterId(),
                    line.getPppoeUsername(),
                    line.getState().name(),
                    decision.profile().name(),
                    DisplayStatus.of(facts, decision, now).name(),
                    line.getExpiresAt(),
                    line.getGraceDays(),
                    line.getPausedAt(),
                    line.getPauseUntil(),
                    line.getActivatedAt(),
                    line.getTerminatedAt(),
                    line.getCreatedAt(),
                    line.getUpdatedAt());
        }
    }
}
