package com.corebalt.isp.subscriptions.domain;

import java.time.Duration;
import java.time.Instant;

/**
 * Why a line's access is limited, beside its {@link AccessProfile} (decisions.md 2026-10-21), so
 * screens and SMS can say it. Null for FULL and REJECT: a rejected line's stored state says why. The
 * SQL copy is {@code subscription_access_v3()} (S3).
 */
public enum AccessReason {
    /** RESTRICTED: past grace. */
    EXPIRED,
    /** RESTRICTED: paused by the customer (S7). */
    PAUSED,
    /** THROTTLED: inside grace on a tenant whose grace mode is THROTTLED. */
    GRACE_THROTTLE,
    /** THROTTLED: past the fair-use allowance in this cycle (N9). */
    FAIR_USE,
    /** RESTRICTED: the data cap of this cycle is used up (N9, Q14). */
    DATA_CAP;

    public static AccessReason of(final AccessPolicy.Facts line, final AccessPolicy.Decision decision, final Instant now) {
        final boolean beforeGraceEnds = decision.effectiveExpiresAt() != null && now.isBefore(
                decision.effectiveExpiresAt().plus(Duration.ofDays(line.graceDays())));
        return switch (decision.profile()) {
            case RESTRICTED -> {
                if (decision.effectiveState() == SubscriptionState.PAUSED) {
                    yield PAUSED;
                }
                yield line.dataCapReached() && beforeGraceEnds ? DATA_CAP : EXPIRED;
            }
            case THROTTLED -> line.fairUseExceeded() && beforeGraceEnds ? FAIR_USE : GRACE_THROTTLE;
            case FULL, REJECT -> null;
        };
    }
}
