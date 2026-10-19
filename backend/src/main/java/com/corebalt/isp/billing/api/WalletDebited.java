package com.corebalt.isp.billing.api;

import com.corebalt.isp.shared.outbox.DomainEvent;
import java.time.Instant;
import java.util.UUID;

/** Money was taken from a wallet (a renewal or proration charge, or a negative adjustment). */
public record WalletDebited(
        UUID eventId,
        UUID tenantId,
        Instant occurredAt,
        UUID customerId,
        UUID walletId,
        UUID ledgerEntryId,
        String entryType,
        long amountMinor,
        String currency,
        String referenceType,
        UUID referenceId)
        implements DomainEvent {}
