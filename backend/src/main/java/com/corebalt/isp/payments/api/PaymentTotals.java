package com.corebalt.isp.payments.api;

import com.corebalt.isp.shared.money.Money;
import java.time.Instant;

/** Money received, for the dashboard; current tenant only. */
public interface PaymentTotals {

    /**
     * Payments received in [from, to), every channel including manual ones (P4), reversed ones left out.
     * {@code unmatched} is the part still in the suspense queue (P3).
     */
    Collected collected(Instant from, Instant to);

    record Collected(long payments, Money amount, long unmatchedPayments, Money unmatchedAmount) {}
}
