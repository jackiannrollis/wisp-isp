package com.corebalt.isp.subscriptions.api;

import com.corebalt.isp.shared.outbox.DomainEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * A line was renewed and charged (docs/04 Renewal rules). {@code renewalId} is the ledger
 * reference of the charge; {@code previousExpiresAt} is null for a first activation;
 * {@code proRataDays} is set when the tenant's PRO_RATA_DAYS rule bought fewer days than a full
 * cycle. Amount in minor units (B2). Carries IDs only (E3).
 */
public record SubscriptionRenewed(
        UUID eventId,
        UUID tenantId,
        Instant occurredAt,
        UUID subscriptionId,
        UUID renewalId,
        UUID customerId,
        UUID packageId,
        Instant previousExpiresAt,
        Instant expiresAt,
        long amountMinor,
        String currency,
        Integer proRataDays)
        implements DomainEvent {}
