package com.corebalt.isp.notifications.application;

import com.corebalt.isp.network.api.RouterOffline;
import com.corebalt.isp.network.api.RouterOnline;
import com.corebalt.isp.shared.id.UuidV7;
import com.corebalt.isp.shared.tenant.TenantContext;
import java.sql.Timestamp;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Staff alerts for routers (docs/06 Monitoring): one open ROUTER_OFFLINE alert per router while it
 * is offline, cleared when it answers again. Runs in the consumer's transaction (E2).
 */
@Service
public class RouterAlerts {

    private static final Logger log = LoggerFactory.getLogger(RouterAlerts.class);

    private final JdbcClient jdbc;
    private final Clock clock;

    RouterAlerts(final JdbcClient jdbc, final Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public void offline(final RouterOffline event) {
        final int raised = jdbc.sql("""
                        insert into notifications_alert (id, tenant_id, kind, subject_id, detail, raised_at)
                        values (:id, :tenantId, 'ROUTER_OFFLINE', :routerId, :detail, :at)
                        on conflict (tenant_id, kind, coalesce(subject_id, '00000000-0000-0000-0000-000000000000'::uuid))
                            where cleared_at is null do nothing
                        """)
                .param("id", UuidV7.next(clock))
                .param("tenantId", TenantContext.requireTenantId())
                .param("routerId", event.routerId())
                .param("detail", "router did not answer 3 polls since " + event.offlineSince())
                .param("at", Timestamp.from(event.occurredAt()))
                .update();
        if (raised == 1) {
            log.error("Router {} of tenant {} is offline", event.routerId(), TenantContext.requireTenantId());
        }
    }

    public void online(final RouterOnline event) {
        jdbc.sql("""
                        update notifications_alert set cleared_at = :at
                        where kind = 'ROUTER_OFFLINE' and subject_id = :routerId and cleared_at is null
                        """)
                .param("at", Timestamp.from(event.occurredAt()))
                .param("routerId", event.routerId())
                .update();
    }
}
