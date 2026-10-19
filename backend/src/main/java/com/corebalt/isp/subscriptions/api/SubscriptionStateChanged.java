package com.corebalt.isp.subscriptions.api;

import com.corebalt.isp.shared.outbox.DomainEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * A stored state changed (S9). The network module turns it into a CoA or Disconnect, except when
 * {@code cause} is {@link Cause#AUTO_RESUME_RECORDED}: the RADIUS view already resumed that line at
 * {@code pause_until} (E4), so the customer's fresh session must not be dropped. Carries IDs only (E3).
 */
public record SubscriptionStateChanged(
        UUID eventId,
        UUID tenantId,
        Instant occurredAt,
        UUID subscriptionId,
        UUID customerId,
        String fromState,
        String toState,
        Cause cause)
        implements DomainEvent {

    public enum Cause {
        /** Staff or the customer acted (pause, resume before pause_until, suspend, lift, terminate). */
        STAFF_ACTION,
        /** A paid renewal activated the line. */
        RENEWAL,
        /** A job recorded a resume the RADIUS view had already applied: no CoA or Disconnect. */
        AUTO_RESUME_RECORDED
    }
}
