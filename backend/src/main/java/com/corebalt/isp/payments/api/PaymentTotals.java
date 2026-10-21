package com.corebalt.isp.payments.api;

import com.corebalt.isp.shared.money.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/** Money received, for the dashboard; current tenant only. */
public interface PaymentTotals {

    /**
     * Payments received in [from, to), every channel including manual ones (P4), reversed ones left out.
     * {@code unmatched} is the part still in the suspense queue (P3).
     */
    Collected collected(Instant from, Instant to);

    /**
     * The same payments per day in {@code zone}, oldest first; days without payments are absent. The
     * days add up to {@link #collected} over the same period.
     */
    List<Day> collectedByDay(Instant from, Instant to, ZoneId zone);

    /** Everything in the suspense queue now (P3), whenever it arrived. */
    Suspense suspense();

    record Collected(long payments, Money amount, long unmatchedPayments, Money unmatchedAmount) {}

    record Day(LocalDate day, long payments, Money amount) {}

    record Suspense(long payments, Money amount) {}
}
