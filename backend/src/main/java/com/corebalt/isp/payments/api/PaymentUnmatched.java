package com.corebalt.isp.payments.api;

import com.corebalt.isp.shared.outbox.DomainEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * A payment no rule could match landed in the suspense queue (P3). Carries the ID only: the payer's
 * name, masked number and reference are read from the suspense screen, never put in events (M3).
 */
public record PaymentUnmatched(UUID eventId, UUID tenantId, Instant occurredAt, UUID paymentId) implements DomainEvent {}
