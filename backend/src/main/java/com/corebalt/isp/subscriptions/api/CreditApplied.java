package com.corebalt.isp.subscriptions.api;

import com.corebalt.isp.shared.outbox.DomainEvent;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * What a credit to the wallet did, decided after the renewals it triggered (docs/01 core flow:
 * one SMS confirms the amount and the new expiry). Published once per WalletCredited.
 * {@code referenceId} is the payment ID for a PAYMENT (the dedupe key of its SMS, M1) and the
 * adjustment ID for an ADJUSTMENT. {@code nextDue} is the first renewable line the balance did not
 * cover, with the amount still missing; null when nothing is waiting. Carries IDs and amounts (E3).
 */
public record CreditApplied(
        UUID eventId,
        UUID tenantId,
        Instant occurredAt,
        UUID customerId,
        String entryType,
        UUID referenceId,
        long amountMinor,
        String currency,
        List<RenewedLine> renewed,
        long balanceAfterMinor,
        Due nextDue)
        implements DomainEvent {

    /**
     * {@code previousProfile}: RESTRICTED means access was restored; REJECT means first activation.
     * {@code previousReason} is why a RESTRICTED or THROTTLED line was limited before (an
     * {@code AccessReason}: EXPIRED, DATA_CAP, GRACE_THROTTLE, FAIR_USE), null otherwise and in events
     * published before it was added (ADR 0018).
     */
    public record RenewedLine(UUID subscriptionId, UUID packageId, Instant expiresAt, String previousProfile,
                              String previousReason) {

        public boolean restored() {
            return "RESTRICTED".equals(previousProfile);
        }
    }

    public record Due(UUID subscriptionId, UUID packageId, long amountDueMinor) {}
}
