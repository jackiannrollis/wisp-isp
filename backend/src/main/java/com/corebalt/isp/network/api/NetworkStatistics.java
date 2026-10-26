package com.corebalt.isp.network.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

    /**
     * When the line's first session in [from, to] started (radacct, docs/06 Accounting): a customer
     * back online after a restore (Gate 1, ADR 0018). Empty when none started.
     */
    Optional<Instant> firstSessionStart(UUID subscriptionId, Instant from, Instant to);

    /**
     * Whether the line had a live session at {@code at} (radacct, the same 48-hour window AccessChanges
     * uses): a line without one needs no Disconnect when it is restored, its next login is FULL (ADR 0018).
     */
    boolean sessionOpenAt(UUID subscriptionId, Instant at);

    /** Input is what the router received from the customer (upload), output what it sent (download). */
    record UsageTotals(long inputOctets, long outputOctets) {}

    record UsageDay(LocalDate day, long inputOctets, long outputOctets) {}
}
