package com.corebalt.isp.billing.api;

import com.corebalt.isp.shared.outbox.DomainEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * Money was added to a wallet (a PAYMENT or a positive ADJUSTMENT). Subscriptions renews the
 * customer's lines from it, reading the balance through {@link Wallets} under the wallet lock,
 * never from this event. {@code referenceId} is the cause: the payment ID for a PAYMENT, the
 * adjustment ID for an ADJUSTMENT. Carries IDs and amounts only (E3).
 */
public record WalletCredited(
        UUID eventId,
        UUID tenantId,
        Instant occurredAt,
        UUID customerId,
        UUID walletId,
        UUID ledgerEntryId,
        String entryType,
        UUID referenceId,
        long amountMinor,
        String currency)
        implements DomainEvent {}
