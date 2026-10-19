package com.corebalt.isp.network.api;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Router status, live sessions and usage for the dashboard and portal; current tenant only. */
public interface NetworkStatistics {

    /** Routers by monitoring status (NOT_MONITORED, UNKNOWN, ONLINE, OFFLINE); every status appears. */
    Map<String, Long> routersByStatus();

    /** radacct sessions with no stop time (docs/06 Accounting); the stale job closes silent ones after 15 minutes. */
    long openSessions();

    /** Octets for tenant-local days from {@code from} to {@code to}, both included, from network_usage_daily only (N7). */
    UsageTotals usage(LocalDate from, LocalDate to);

    /** One line's days, oldest first; days without traffic are absent (N7). */
    List<UsageDay> dailyUsage(UUID subscriptionId, LocalDate from, LocalDate to);

    /** Input is what the router received from the customer (upload), output what it sent (download). */
    record UsageTotals(long inputOctets, long outputOctets) {}

    record UsageDay(LocalDate day, long inputOctets, long outputOctets) {}
}
