package com.corebalt.isp.identity.application;

import com.corebalt.isp.identity.api.AuditEntry;
import com.corebalt.isp.identity.api.AuditTrail;
import com.corebalt.isp.shared.id.UuidV7;
import com.corebalt.isp.shared.tenant.TenantContext;
import com.corebalt.isp.shared.web.CorrelationIdFilter;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Inserts into {@code identity_audit_log} (insert-only grant, A1). The actor is the staff member of
 * the request's JWT; without one (jobs, consumers) it is {@code SYSTEM}. Staff and roles come with
 * the identity step; until then a {@code sub} that is not a UUID is recorded as a STAFF actor
 * without an ID.
 */
@Component
class JdbcAuditTrail implements AuditTrail {

    private final JdbcClient jdbc;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    JdbcAuditTrail(final JdbcClient jdbc, final JsonMapper jsonMapper, final Clock clock) {
        this.jdbc = jdbc;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(final AuditEntry entry) {
        final boolean staff =
                SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken;
        jdbc.sql(
                        """
                        insert into identity_audit_log
                            (id, tenant_id, actor_type, actor_id, action, target_type, target_id,
                             before_state, after_state, reason, correlation_id, occurred_at)
                        values (:id, :tenantId, :actorType, :actorId, :action, :targetType, :targetId,
                                cast(:before as jsonb), cast(:after as jsonb), :reason, :correlationId,
                                :occurredAt)
                        """)
                .param("id", UuidV7.next(clock))
                .param("tenantId", TenantContext.requireTenantId())
                .param("actorType", staff ? "STAFF" : "SYSTEM")
                .param("actorId", staff ? staffId() : null)
                .param("action", entry.action())
                .param("targetType", entry.targetType())
                .param("targetId", entry.targetId())
                .param("before", entry.before() == null ? null : jsonMapper.writeValueAsString(entry.before()))
                .param("after", entry.after() == null ? null : jsonMapper.writeValueAsString(entry.after()))
                .param("reason", entry.reason())
                .param("correlationId", MDC.get(CorrelationIdFilter.MDC_KEY))
                .param("occurredAt", Timestamp.from(clock.instant()))
                .update();
    }

    private static UUID staffId() {
        final String subject =
                ((JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication())
                        .getToken()
                        .getSubject();
        try {
            return subject == null ? null : UUID.fromString(subject);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
