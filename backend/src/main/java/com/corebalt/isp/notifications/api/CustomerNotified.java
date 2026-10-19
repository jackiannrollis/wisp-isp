package com.corebalt.isp.notifications.api;

import com.corebalt.isp.shared.outbox.DomainEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * A message left the platform (accepted by the SMS provider): the last step of the core flow
 * (docs/01). {@code customerId} is null for a payer who is not a known customer. IDs only (E3, M3).
 */
public record CustomerNotified(
        UUID eventId, UUID tenantId, Instant occurredAt, UUID notificationId, UUID customerId, String templateKey)
        implements DomainEvent {}
