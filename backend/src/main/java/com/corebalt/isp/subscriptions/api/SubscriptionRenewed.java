package com.corebalt.isp.subscriptions.api;

import com.corebalt.isp.shared.outbox.DomainEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * A line was renewed and charged (docs/04 Renewal rules). {@code previousExpiresAt} is null for a
 * first activation. Amount in minor units (B2). Carries IDs only (E3).
 */
public record SubscriptionRenewed(
        UUID eventId,
        UUID tenantId,
        Instant occurredAt,
        UUID subscriptionId,
        UUID customerId,
        UUID packageId,
        Instant previousExpiresAt,
        Instant expiresAt,
        long amountMinor,
        String currency)
        implements DomainEvent {}
