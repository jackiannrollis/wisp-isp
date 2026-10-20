package com.corebalt.isp.payments.api;

import com.corebalt.isp.shared.money.MoneyDto;
import java.time.Instant;
import java.util.UUID;

public record PaymentDto(
        UUID id,
        String channel,
        String reference,
        MoneyDto amount,
        Instant receivedAt,
        String status,
        UUID customerId,
        String customerNumber,
        String customerName,
        Instant matchedAt) {}
