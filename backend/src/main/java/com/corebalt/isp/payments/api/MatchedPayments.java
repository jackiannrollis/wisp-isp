package com.corebalt.isp.payments.api;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Matched payments of the current tenant, for billing's check that every one of them reached the
 * ledger (a payment without a PAYMENT entry means a stuck or dead-lettered consumer), and for Gate 1's
 * manual reconnects (ADR 0018).
 */
public interface MatchedPayments {

    List<Matched> matchedBetween(Instant from, Instant to);

    /** The customer's payment matched last at or before {@code at}, any channel; empty when none. */
    Optional<Matched> latestFor(UUID customerId, Instant at);

    record Matched(UUID paymentId, UUID customerId, long amountMinor, Instant matchedAt) {}
}
