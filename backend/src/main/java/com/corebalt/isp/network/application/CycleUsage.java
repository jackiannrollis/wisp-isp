package com.corebalt.isp.network.application;

import com.corebalt.isp.network.api.UsageThresholdReached;
import com.corebalt.isp.packages.api.PackageCatalog;
import com.corebalt.isp.packages.api.PackageCatalog.PackageLimits;
import com.corebalt.isp.shared.id.UuidV7;
import com.corebalt.isp.shared.outbox.OutboxPublisher;
import com.corebalt.isp.shared.tenant.TenantContext;
import com.corebalt.isp.subscriptions.api.LineUsage;
import com.corebalt.isp.subscriptions.api.SubscriptionDirectory;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Each line's usage in its current cycle (docs/09 step 8b), kept in network_line_usage from the growth
 * the usage roll-up counts (N7), and the announcement when it passes the package's fair-use allowance
 * or data cap. {@link CycleUsageReader} reads the same totals for subscriptions' {@link LineUsage} port.
 *
 * <p>The cycle is the line's, from its last renewal (Q12 default); a counter still holding an older
 * cycle reads as zero and restarts at the next roll-up, so a renewal lifts a limit the moment it
 * commits (Q13 default). A line whose cycle start is unknown is not counted.
 */
@Service
public class CycleUsage {

    private final JdbcClient jdbc;
    private final SubscriptionDirectory lines;
    private final PackageCatalog packages;
    private final OutboxPublisher outbox;
    private final Clock clock;

    CycleUsage(final JdbcClient jdbc, final SubscriptionDirectory lines, final PackageCatalog packages,
               final OutboxPublisher outbox, final Clock clock) {
        this.jdbc = jdbc;
        this.lines = lines;
        this.packages = packages;
        this.outbox = outbox;
        this.clock = clock;
    }

    /**
     * Adds a roll-up's growth to each line's cycle total, restarting it when the line has renewed since,
     * and announces a line passing its limits, once per cycle and kind. Runs in the roll-up's
     * transaction, so the growth is counted here exactly when it is counted per day (N7, E1).
     */
    void add(final Map<UUID, Long> growth) {
        final Instant now = clock.instant();
        final Map<UUID, PackageLimits> limits = new HashMap<>();
        for (final Map.Entry<UUID, Long> line : growth.entrySet()) {
            final Optional<SubscriptionDirectory.LineInfo> info = lines.line(line.getKey());
            if (info.isEmpty() || info.get().cycleStartedAt() == null) {
                continue;
            }
            final Counter counter = jdbc.sql("""
                            insert into network_line_usage (tenant_id, subscription_id, cycle_started_at, octets, updated_at)
                            values (:tenantId, :id, :cycle, :octets, :now)
                            on conflict (tenant_id, subscription_id) do update
                            set octets = case when network_line_usage.cycle_started_at = excluded.cycle_started_at
                                              then network_line_usage.octets + excluded.octets else excluded.octets end,
                                fair_use_reached_at = case when network_line_usage.cycle_started_at = excluded.cycle_started_at
                                                           then network_line_usage.fair_use_reached_at end,
                                data_cap_reached_at = case when network_line_usage.cycle_started_at = excluded.cycle_started_at
                                                           then network_line_usage.data_cap_reached_at end,
                                cycle_started_at = excluded.cycle_started_at,
                                updated_at = excluded.updated_at
                            returning octets, fair_use_reached_at is not null, data_cap_reached_at is not null
                            """)
                    .param("tenantId", TenantContext.requireTenantId())
                    .param("id", line.getKey())
                    .param("cycle", Timestamp.from(info.get().cycleStartedAt()))
                    .param("octets", line.getValue())
                    .param("now", Timestamp.from(now))
                    .query((rs, n) -> new Counter(rs.getLong(1), rs.getBoolean(2), rs.getBoolean(3)))
                    .single();
            final PackageLimits packageLimits = limits.computeIfAbsent(info.get().packageId(),
                    id -> packages.limits(id).orElse(PackageLimits.NONE));
            if (!counter.fairUseAnnounced() && packageLimits.fairUseBytes() != null
                    && counter.octets() >= packageLimits.fairUseBytes()) {
                announce(info.get(), UsageThresholdReached.Kind.FAIR_USE, counter.octets(), packageLimits.fairUseBytes(), now);
            }
            if (!counter.dataCapAnnounced() && packageLimits.dataCapBytes() != null
                    && counter.octets() >= packageLimits.dataCapBytes()) {
                announce(info.get(), UsageThresholdReached.Kind.DATA_CAP, counter.octets(), packageLimits.dataCapBytes(), now);
            }
        }
    }

    private void announce(final SubscriptionDirectory.LineInfo line, final UsageThresholdReached.Kind kind,
                          final long used, final long limit, final Instant now) {
        final String column = kind == UsageThresholdReached.Kind.FAIR_USE ? "fair_use_reached_at" : "data_cap_reached_at";
        jdbc.sql("update network_line_usage set " + column + " = :now where subscription_id = :id")
                .param("now", Timestamp.from(now))
                .param("id", line.id())
                .update();
        outbox.publish(new UsageThresholdReached(UuidV7.next(clock), TenantContext.requireTenantId(), now, line.id(),
                line.customerId(), kind, line.cycleStartedAt(), used, limit));
    }

    private record Counter(long octets, boolean fairUseAnnounced, boolean dataCapAnnounced) {}
}
