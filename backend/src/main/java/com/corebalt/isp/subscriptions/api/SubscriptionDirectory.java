package com.corebalt.isp.subscriptions.api;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Read-only facts about a line for other modules (notifications wording, reports); current tenant only. */
public interface SubscriptionDirectory {

    Optional<LineInfo> line(UUID subscriptionId);

    /**
     * The access profile (S2: FULL, THROTTLED, RESTRICTED or REJECT) the line has at {@code at}, judged by
     * {@code AccessPolicy} on its stored state and grace, with {@code expiresAt} in place of the stored
     * expiry when not null. The network module compares the profile before and after a renewal with it
     * (N5) without a copy of the rules (S3).
     */
    Optional<String> accessProfile(UUID subscriptionId, Instant at, Instant expiresAt);

    /**
     * The profile at {@code at} as it was just before a usage limit was crossed (N9): before the
     * data cap ({@code dataCap} true) fair use still counts; before fair use no limit does. The network
     * module compares it with {@link #accessProfile} to skip a Disconnect that would change nothing.
     */
    Optional<String> accessProfileBeforeLimit(UUID subscriptionId, Instant at, boolean dataCap);

    /**
     * {@code nextPackageId} is what the next renewal sells (a pending change, else the current package);
     * {@code cycleStartedAt} is when the current cycle began, null while unknown (docs/09 step 8b).
     */
    record LineInfo(
            UUID id, UUID customerId, String pppoeUsername, UUID packageId, UUID nextPackageId, Instant expiresAt,
            int graceDays, Instant cycleStartedAt) {}
}
