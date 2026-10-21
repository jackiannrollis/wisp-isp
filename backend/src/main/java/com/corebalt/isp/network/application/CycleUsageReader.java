package com.corebalt.isp.network.application;

import com.corebalt.isp.subscriptions.api.LineUsage;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Subscriptions' {@link LineUsage} port (docs/09 step 8b): a line's usage in its current cycle, as
 * {@link CycleUsage} keeps it, read the way v_radius_auth_v6 reads it, so AccessPolicy and the RADIUS
 * view judge the same usage (S3). Depends on nothing but the database, because subscriptions calls it
 * while building its own views.
 */
@Service
class CycleUsageReader implements LineUsage {

    private final JdbcClient jdbc;

    CycleUsageReader(final JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public long cycleOctets(final UUID subscriptionId, final Instant cycleStartedAt) {
        if (cycleStartedAt == null) {
            return 0;
        }
        return jdbc.sql("""
                        select octets from network_line_usage
                        where subscription_id = :id and cycle_started_at = :cycle
                        """)
                .param("id", subscriptionId)
                .param("cycle", Timestamp.from(cycleStartedAt))
                .query(Long.class)
                .optional()
                .orElse(0L);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, Long> cycleOctets(final Map<UUID, Instant> cycleStarts) {
        final Map<UUID, Long> octets = new HashMap<>();
        if (cycleStarts.isEmpty()) {
            return octets;
        }
        jdbc.sql("select subscription_id, cycle_started_at, octets from network_line_usage where subscription_id in (:ids)")
                .param("ids", List.copyOf(cycleStarts.keySet()))
                .query(rs -> {
                    final UUID id = rs.getObject(1, UUID.class);
                    if (rs.getTimestamp(2).toInstant().equals(cycleStarts.get(id))) {
                        octets.put(id, rs.getLong(3));
                    }
                });
        return octets;
    }

}
