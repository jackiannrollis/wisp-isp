package com.corebalt.isp.payments.api;

import com.corebalt.isp.shared.money.MoneyDto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

/**
 * A payment staff record by hand (P5): cash, bank, or an M-Pesa payment before the tenant's Daraja
 * app is live. {@code reference} is the receipt-book number, bank reference or M-Pesa TransID and is
 * unique per tenant (P1). {@code receivedAt} defaults to now.
 */
public record ManualPaymentRequest(
        @NotNull UUID customerId,
        @NotNull @Valid MoneyDto amount,
        @NotNull @Pattern(regexp = "CASH|BANK|MPESA_PAYBILL") String channel,
        @NotBlank @Size(max = 64) String reference,
        Instant receivedAt,
        @Size(max = 200) String payerName,
        @Size(max = 500) String note) {}
