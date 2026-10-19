package com.corebalt.isp.subscriptions.api;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Read-only facts about a line for other modules (notifications wording, reports); current tenant only. */
public interface SubscriptionDirectory {

    Optional<LineInfo> line(UUID subscriptionId);

    /** {@code nextPackageId} is what the next renewal sells (a pending change, else the current package). */
    record LineInfo(
            UUID id, UUID customerId, String pppoeUsername, UUID packageId, UUID nextPackageId, Instant expiresAt,
            int graceDays) {}
}
