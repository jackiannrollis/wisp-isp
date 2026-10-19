package com.corebalt.isp.payments.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Money a provider reports, before matching (docs/05). {@code transactionId} is the M-Pesa TransID
 * or receipt number (P1); {@code knownCustomerId} is set when the request already identified the
 * customer (STK Push); {@code rawJson} is the body as received (P2).
 */
public record IncomingPayment(
        String channel,
        String transactionId,
        long amountMinor,
        Instant receivedAt,
        String accountRef,
        String msisdn,
        String payerName,
        String rawJson,
        UUID knownCustomerId) {}
