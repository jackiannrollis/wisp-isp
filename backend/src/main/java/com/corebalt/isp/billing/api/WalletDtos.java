package com.corebalt.isp.billing.api;

import com.corebalt.isp.shared.money.MoneyDto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

/** API shapes of the billing module. */
public final class WalletDtos {

    private WalletDtos() {}

    public record WalletDto(UUID customerId, MoneyDto balance) {}

    public record LedgerEntryDto(
            UUID id,
            String type,
            MoneyDto amount,
            MoneyDto balanceAfter,
            String referenceType,
            UUID referenceId,
            String description,
            String reason,
            Instant createdAt) {}

    /**
     * A correction or a grant (B1): positive credits the wallet (and renews like a payment), negative
     * debits it, never below zero. Always with a reason.
     */
    public record AdjustmentRequest(@NotNull @Valid MoneyDto amount, @NotBlank @Size(max = 500) String reason) {}

    public record ReceiptDto(
            UUID id, String number, UUID paymentId, UUID customerId, MoneyDto amount, Instant issuedAt) {}
}
