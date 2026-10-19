package com.corebalt.isp.network.application;

import com.corebalt.isp.network.api.NetworkStatistics;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reads for the dashboard and portal; row-level security keeps them to the current tenant (T1). */
@Service
public class NetworkStatisticsService implements NetworkStatistics {

    private static final List<String> STATUSES = List.of("ONLINE", "OFFLINE", "UNKNOWN", "NOT_MONITORED");

    private final JdbcClient jdbc;

    NetworkStatisticsService(final JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Long> routersByStatus() {
        final Map<String, Long> counts = new LinkedHashMap<>();
        STATUSES.forEach(status -> counts.put(status, 0L));
        jdbc.sql("select status, count(*) from network_router group by status")
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
}
