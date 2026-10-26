package com.corebalt.isp.network.application;

import com.corebalt.isp.network.api.NetworkStatistics;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reads for the dashboard and portal; row-level security keeps them to the current tenant (T1). */
@Service
public class NetworkStatisticsService implements NetworkStatistics {

    private static final List<String> STATUSES = List.of("ONLINE", "OFFLINE", "UNKNOWN", "NOT_MONITORED");
    /** Sessions last at most a day (S4); an older row without a stop time is stale, as in AccessChanges. */
    private static final Duration LIVE_WINDOW = Duration.ofHours(48);

    private final JdbcClient jdbc;

    NetworkStatisticsService(final JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Long> routersByStatus() {
        final Map<String, Long> counts = new LinkedHashMap<>();
        STATUSES.forEach(status -> counts.put(status, 0L));
        // Retired routers (N10) are history, not part of the network.
        jdbc.sql("select status, count(*) from network_router where retired_at is null group by status")
                .query(rs -> {
                    counts.put(rs.getString(1), rs.getLong(2));
                });
        return counts;
    }

    @Override
    @Transactional(readOnly = true)
    public long openSessions() {
        return jdbc.sql("select count(*) from radacct where acctstoptime is null").query(Long.class).single();
    }

    @Override
    @Transactional(readOnly = true)
    public UsageTotals usage(final LocalDate from, final LocalDate to) {
        return jdbc.sql("""
                        select coalesce(sum(input_octets), 0), coalesce(sum(output_octets), 0)
                        from network_usage_daily where day between :from and :to
                        """)
                .param("from", from)
                .param("to", to)
                .query((rs, n) -> new UsageTotals(rs.getLong(1), rs.getLong(2)))
                .single();
    }

    @Override
    @Transactional(readOnly = true)
    public List<UsageDay> dailyUsage(final UUID subscriptionId, final LocalDate from, final LocalDate to) {
        return jdbc.sql("""
                        select day, input_octets, output_octets from network_usage_daily
                        where subscription_id = :id and day between :from and :to
                        order by day
                        """)
                .param("id", subscriptionId)
                .param("from", from)
                .param("to", to)
                .query((rs, n) -> new UsageDay(rs.getObject(1, LocalDate.class), rs.getLong(2), rs.getLong(3)))
                .list();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Instant> firstSessionStart(final UUID subscriptionId, final Instant from, final Instant to) {
        return jdbc.sql("""
                        select min(acctstarttime) from radacct
                        where subscription_id = :id and acctstarttime >= :from and acctstarttime <= :to
                        """)
                .param("id", subscriptionId)
                .param("from", Timestamp.from(from))
                .param("to", Timestamp.from(to))
                .query(Timestamp.class)
                .optional()
                .map(Timestamp::toInstant);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean sessionOpenAt(final UUID subscriptionId, final Instant at) {
        return jdbc.sql("""
                        select exists (select 1 from radacct
                                       where subscription_id = :id and router_id is not null
                                         and acctstarttime <= :at and acctstarttime > :since
                                         and (acctstoptime is null or acctstoptime > :at))
                        """)
                .param("id", subscriptionId)
                .param("at", Timestamp.from(at))
                .param("since", Timestamp.from(at.minus(LIVE_WINDOW)))
                .query(Boolean.class)
                .single();
    }
}
