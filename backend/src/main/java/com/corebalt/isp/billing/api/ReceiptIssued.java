package com.corebalt.isp.billing.api;

import com.corebalt.isp.shared.outbox.DomainEvent;
import java.time.Instant;
import java.util.UUID;

/** A receipt was issued for a credited payment (B7); the PAYMENT_RECEIVED SMS quotes its number. */
public record ReceiptIssued(
        UUID eventId,
        UUID tenantId,
        Instant occurredAt,
        UUID receiptId,
        String number,
        UUID paymentId,
        UUID customerId,
        long amountMinor,
        String currency)
        implements DomainEvent {}
