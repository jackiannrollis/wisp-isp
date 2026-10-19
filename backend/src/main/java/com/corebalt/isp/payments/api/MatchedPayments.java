package com.corebalt.isp.payments.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Matched payments of the current tenant, for billing's check that every one of them reached the
 * ledger: a payment without a PAYMENT entry means a stuck or dead-lettered consumer.
 */
public interface MatchedPayments {

    List<Matched> matchedBetween(Instant from, Instant to);

    record Matched(UUID paymentId, UUID customerId, long amountMinor, Instant matchedAt) {}
}
