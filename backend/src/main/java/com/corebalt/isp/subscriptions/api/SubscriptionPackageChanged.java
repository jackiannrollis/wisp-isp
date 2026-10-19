package com.corebalt.isp.subscriptions.api;

import com.corebalt.isp.shared.outbox.DomainEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * A line's package changed now (S10: the network module sends a CoA with the new speed) or was
 * scheduled for the next renewal ({@code timing} NEXT_RENEWAL: nothing changes on the router yet).
 * {@code prorationMinor} is what an immediate upgrade charged (B6).
 */
public record SubscriptionPackageChanged(
        UUID eventId,
        UUID tenantId,
        Instant occurredAt,
        UUID subscriptionId,
        UUID customerId,
        UUID fromPackageId,
        UUID toPackageId,
        String timing,
        long prorationMinor,
        String currency)
        implements DomainEvent {}
