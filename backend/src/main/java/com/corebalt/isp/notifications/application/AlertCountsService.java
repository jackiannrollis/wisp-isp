package com.corebalt.isp.notifications.application;

import com.corebalt.isp.notifications.api.AlertCounts;
import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Counts over notifications_alert; row-level security keeps them to the current tenant (T1). */
@Service
class AlertCountsService implements AlertCounts {

    private final JdbcClient jdbc;

    AlertCountsService(final JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public Raised raised(final Instant from, final Instant to) {
        return jdbc.sql("""
                        select count(*) filter (where kind = 'COA_FAILED'), count(*) filter (where kind = 'ROUTER_OFFLINE')
                        from notifications_alert
                        where raised_at >= :from and raised_at < :to
                        """)
                .param("from", Timestamp.from(from))
                .param("to", Timestamp.from(to))
                .query((rs, n) -> new Raised(rs.getLong(1), rs.getLong(2)))
                .single();
    }
}
