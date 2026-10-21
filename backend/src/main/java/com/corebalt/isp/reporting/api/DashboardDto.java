package com.corebalt.isp.reporting.api;

import com.corebalt.isp.shared.money.MoneyDto;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * The admin dashboard's counters (docs/09 step 8) for tenant-local days {@code from} to {@code to},
 * both included. Line counts, routers and open sessions are as of {@code asOf}, not the period.
 *
 * <p>{@code revenue} holds every money figure and is left out (absent, not null) for callers without
 * {@code finance.read}, so one endpoint serves every role (docs/05 Dashboard figures):
 *
 * <ul>
 *   <li>{@code renewalsDue}: ACTIVE lines whose expiry falls in the period and the price their next
 *       renewal sells (the dashboard's "expected revenue").
 *   <li>{@code outstanding}: what the wallets of those still-unrenewed lines lack for their renewal.
 *   <li>{@code lapsed}: lines stored ACTIVE whose expiry fell before the period and that have not
 *       renewed, and the price of their next renewal (the churn list).
 *   <li>{@code collected}: payments received in the period, every channel, reversals left out;
 *       {@code unmatched} is the part still in suspense (P3). {@code collectedByDay} splits it per
 *       tenant-local day, every day of the period up to today, zero days included.
 * </ul>
 *
 * <p>{@code suspense} is everything in the suspense queue now, whatever the period, for callers with
 * {@code finance.read} or {@code payments.assign}: the dashboard's action queue leads them to it.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DashboardDto(
        LocalDate from,
        LocalDate to,
        String zone,
        Instant asOf,
        Lines lines,
        Revenue revenue,
        Suspense suspense,
        Network network) {

    /** {@code pendingOver14Days}: lines PENDING for more than 14 days, to cancel or chase (docs/04). */
    public record Lines(long total, Map<String, Long> byState, Map<String, Long> byDisplayStatus, long pendingOver14Days) {}

    public record Revenue(
            long renewalsDueLines,
            MoneyDto renewalsDue,
            MoneyDto outstanding,
            long lapsedLines,
            MoneyDto lapsed,
            long payments,
            MoneyDto collected,
            long unmatchedPayments,
            MoneyDto unmatched,
            List<DayCollected> collectedByDay) {}

    public record DayCollected(LocalDate day, long payments, MoneyDto amount) {}

    public record Suspense(long payments, MoneyDto amount) {}

    public record Network(Map<String, Long> routersByStatus, long activeSessions, long inputOctets, long outputOctets) {}
}
