package com.corebalt.isp.reporting.api;

import com.corebalt.isp.shared.money.MoneyDto;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.time.LocalDate;
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
 *       {@code unmatched} is the part still in suspense (P3).
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DashboardDto(
        LocalDate from,
        LocalDate to,
        String zone,
        Instant asOf,
        Lines lines,
        Revenue revenue,
        Network network) {

    public record Lines(long total, Map<String, Long> byState, Map<String, Long> byDisplayStatus) {}

    public record Revenue(
            long renewalsDueLines,
            MoneyDto renewalsDue,
            MoneyDto outstanding,
            long lapsedLines,
            MoneyDto lapsed,
            long payments,
            MoneyDto collected,
            long unmatchedPayments,
            MoneyDto unmatched) {}

    public record Network(Map<String, Long> routersByStatus, long activeSessions, long inputOctets, long outputOctets) {}
}
