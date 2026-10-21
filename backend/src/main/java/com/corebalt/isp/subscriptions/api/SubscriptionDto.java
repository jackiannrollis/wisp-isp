package com.corebalt.isp.subscriptions.api;

import java.time.Instant;
import java.util.UUID;

/**
 * A line as staff see it. {@code accessProfile}, {@code displayStatus} and {@code accessReason} (why
 * access is limited; null when it is not) are derived at read time (S1) and shown as returned; the
 * frontend never computes them. Never carries the PPPoE password
 * (T4, ADR 0009). {@code usage} is the line's current cycle against its package's limits (docs/09
 * step 8b).
 */
public record SubscriptionDto(
        UUID id,
        UUID customerId,
        UUID packageId,
        UUID routerId,
        String pppoeUsername,
        String state,
        String accessProfile,
        String displayStatus,
        String accessReason,
        Instant expiresAt,
        int graceDays,
        Instant pausedAt,
        Instant pauseUntil,
        Instant activatedAt,
        Instant terminatedAt,
        Instant createdAt,
        Instant updatedAt,
        CycleUsage usage) {

    /**
     * Download plus upload since {@code cycleStartedAt} (the last renewal, Q12 default), from the usage
     * roll-up, so up to about 15 minutes behind (N7). {@code cycleStartedAt} is null while unknown, and
     * such a line is not metered. Limits are null when the package has none.
     */
    public record CycleUsage(
            Instant cycleStartedAt,
            long usedBytes,
            Long fairUseBytes,
            Long dataCapBytes,
            boolean fairUseExceeded,
            boolean dataCapReached) {}
}
