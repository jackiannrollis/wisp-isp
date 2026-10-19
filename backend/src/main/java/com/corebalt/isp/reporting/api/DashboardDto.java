package com.corebalt.isp.reporting.api;

import com.corebalt.isp.shared.money.MoneyDto;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

/**
 * The admin dashboard's counters (docs/09 step 8) for tenant-local days {@code from} to {@code to},
 * both included. Line counts, routers and open sessions are as of {@code asOf}, not the period.
 *
 * <ul>
 *   <li>{@code renewalsDue}: ACTIVE lines whose expiry falls in the period and the price their next
 *       renewal sells. This is the dashboard's "expected revenue"; docs do not define it further.
 *   <li>{@code collected}: payments received in the period, every channel, reversals left out;
 *       {@code unmatched} is the part still in suspense (P3).
 * </ul>
 */
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
            long payments,
            MoneyDto collected,
            long unmatchedPayments,
            MoneyDto unmatched) {}

    public record Network(Map<String, Long> routersByStatus, long activeSessions, long inputOctets, long outputOctets) {}
}
