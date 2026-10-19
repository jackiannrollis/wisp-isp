package com.corebalt.isp.payments.api;

import com.corebalt.isp.shared.outbox.DomainEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * Money arrived for a customer (docs/02): written to the outbox in the same transaction as the
 * payment record (P4). The billing module credits the wallet from it and is the only writer of
 * PAYMENT ledger entries. Payments knows nothing about subscriptions.
 */
public record PaymentReceived(
        UUID eventId,
        UUID tenantId,
        Instant occurredAt,
        UUID paymentId,
        UUID customerId,
        long amountMinor,
        String currency,
        String channel,
        String reference,
        Instant receivedAt)
        implements DomainEvent {}
