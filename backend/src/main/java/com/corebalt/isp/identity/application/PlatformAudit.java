package com.corebalt.isp.identity.application;

import com.corebalt.isp.shared.id.UuidV7;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * The platform audit log (A1), append-only: bootstrap, platform sign-ins, break-glass resets,
 * tenant creation and impersonations. Never holds secrets (A3).
 */
@Component
class PlatformAudit {

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final Clock clock;

    PlatformAudit(final JdbcClient jdbc, final JsonMapper json, final Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    void record(final UUID adminId, final String action, final String targetType, final UUID targetId,
                final Map<String, Object> detail) {
        jdbc.sql("""
                        insert into identity_platform_audit_log (id, admin_id, action, target_type, target_id, detail, occurred_at)
                        values (:id, :admin, :action, :targetType, :targetId, cast(:detail as jsonb), :now)
                        """)
                .param("id", UuidV7.next(clock))
                .param("admin", adminId)
                .param("action", action)
                .param("targetType", targetType)
                .param("targetId", targetId)
                .param("detail", detail == null ? null : json.writeValueAsString(detail))
                .param("now", Timestamp.from(clock.instant()))
                .update();
    }
}
