package com.corebalt.isp.subscriptions.api;

import com.corebalt.isp.shared.outbox.DomainEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * A reminder is due (docs/04 Reminders): {@code eventKey} is EXPIRING_3D, EXPIRING_1D, DUE_TODAY,
 * GRACE_STARTED or RESTRICTED. Raised once per {@code dedupeKey} (M1); the notifications module
 * renders and sends it inside the tenant's sending window (M2).
 */
public record SubscriptionReminderDue(
        UUID eventId,
        UUID tenantId,
        Instant occurredAt,
        UUID subscriptionId,
        UUID customerId,
        String eventKey,
        Instant expiresAt,
        String dedupeKey)
        implements DomainEvent {}
