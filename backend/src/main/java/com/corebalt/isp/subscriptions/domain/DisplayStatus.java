package com.corebalt.isp.subscriptions.domain;

import java.time.Duration;
import java.time.Instant;

/**
 * The status shown on screens and in SMS (docs/04 States), derived at read time from the same
 * inputs as {@link AccessPolicy} and never stored (S1). A line never shows ACTIVE while it is
 * throttled or restricted: fair use shows THROTTLED and a reached data cap CAPPED. The SQL copy is
 * {@code subscription_access_v3()} (S3).
 */
public enum DisplayStatus {
    PENDING,
    ACTIVE,
    EXPIRING,
    GRACE,
    RESTRICTED,
    THROTTLED,
    CAPPED,
    PAUSED,
    SUSPENDED,
    TERMINATED;

    /** EXPIRING starts this long before {@code expires_at}. */
    public static final Duration EXPIRING_WINDOW = Duration.ofDays(3);

    public static DisplayStatus of(final AccessPolicy.Facts line, final AccessPolicy.Decision decision, final Instant now) {
        return switch (decision.effectiveState()) {
            case PENDING -> PENDING;
            case PAUSED -> PAUSED;
            case SUSPENDED -> SUSPENDED;
            case TERMINATED -> TERMINATED;
            case ACTIVE -> {
                final Instant expiresAt = decision.effectiveExpiresAt();
                if (now.isBefore(expiresAt)) {
                    if (line.dataCapReached()) {
                        yield CAPPED;
                    }
                    if (line.fairUseExceeded()) {
                        yield THROTTLED;
                    }
                    yield now.isBefore(expiresAt.minus(EXPIRING_WINDOW)) ? ACTIVE : EXPIRING;
                }
                if (decision.profile() == AccessProfile.RESTRICTED) {
                    yield AccessReason.of(line, decision, now) == AccessReason.DATA_CAP ? CAPPED : RESTRICTED;
                }
                yield GRACE;
            }
        };
    }
}
