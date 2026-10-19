package com.corebalt.isp.notifications.application;

import com.corebalt.isp.network.api.SessionReauthorizationFailed;
import com.corebalt.isp.network.api.SessionReauthorized;
import com.corebalt.isp.shared.id.UuidV7;
import com.corebalt.isp.shared.tenant.TenantContext;
import java.sql.Timestamp;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Staff alerts for CoA and Disconnect (N5, docs/08): one open COA_FAILED alert per line whose request
 * failed after its retries, cleared when a later request for the line is acknowledged. Runs in the
 * consumer's transaction (E2).
 */
@Service
public class CoaAlerts {

    private static final Logger log = LoggerFactory.getLogger(CoaAlerts.class);

    private final JdbcClient jdbc;
    private final Clock clock;

    CoaAlerts(final JdbcClient jdbc, final Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public void failed(final SessionReauthorizationFailed event) {
        final int raised = jdbc.sql("""
                        insert into notifications_alert (id, tenant_id, kind, subject_id, detail, raised_at)
                        values (:id, :tenantId, 'COA_FAILED', :subscriptionId, :detail, :at)
                        on conflict (tenant_id, kind, coalesce(subject_id, '00000000-0000-0000-0000-000000000000'::uuid))
                            where cleared_at is null do nothing
                        """)
                .param("id", UuidV7.next(clock))
                .param("tenantId", TenantContext.requireTenantId())
                .param("subscriptionId", event.subscriptionId())
                .param("detail", event.action() + " on router " + event.routerId() + " failed after " + event.attempts()
                        + " attempt(s): " + event.reason())
                .param("at", Timestamp.from(event.occurredAt()))
                .update();
        if (raised == 1) {
            log.error("{} for line {} failed; it keeps its old profile until Session-Timeout", event.action(),
                    event.subscriptionId());
        }
    }

    public void reauthorized(final SessionReauthorized event) {
        jdbc.sql("""
                        update notifications_alert set cleared_at = :at
                        where kind = 'COA_FAILED' and subject_id = :subscriptionId and cleared_at is null
                        """)
                .param("at", Timestamp.from(event.occurredAt()))
                .param("subscriptionId", event.subscriptionId())
                .update();
    }
}
