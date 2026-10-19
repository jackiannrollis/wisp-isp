package com.corebalt.isp.identity.application;

import com.corebalt.isp.shared.audit.AuditEntry;
import com.corebalt.isp.shared.audit.AuditTrail;
import com.corebalt.isp.shared.id.UuidV7;
import com.corebalt.isp.shared.security.CurrentActor;
import com.corebalt.isp.shared.tenant.TenantContext;
import com.corebalt.isp.shared.web.CorrelationIdFilter;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Inserts into {@code identity_audit_log} (insert-only grant, A1). The actor comes from the access
 * token: a staff member, a portal customer, or, during an impersonation, the platform admin with the
 * staff member in {@code on_behalf_of}, so the tenant sees who really acted. Without a token (jobs,
 * consumers) the actor is {@code SYSTEM}.
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
        final Optional<UUID> platform = CurrentActor.actingPlatformAdmin();
        if (platform.isPresent()) {
            insert("PLATFORM", platform.get(), CurrentActor.staffId().orElse(null), entry);
        } else if (CurrentActor.staffId().isPresent()) {
            insert("STAFF", CurrentActor.staffId().get(), null, entry);
        } else if (CurrentActor.customerId().isPresent()) {
            insert("CUSTOMER", CurrentActor.customerId().get(), null, entry);
        } else {
            insert("SYSTEM", null, null, entry);
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordAs(final String actorType, final UUID actorId, final AuditEntry entry) {
        insert(actorType, actorId, null, entry);
    }

    /** For impersonation start and end: the platform admin acting on behalf of a staff member. */
    @Transactional(propagation = Propagation.MANDATORY)
    void recordPlatform(final UUID adminId, final UUID onBehalfOf, final AuditEntry entry) {
        insert("PLATFORM", adminId, onBehalfOf, entry);
    }

    private void insert(final String actorType, final UUID actorId, final UUID onBehalfOf, final AuditEntry entry) {
        jdbc.sql(
                        """
                        insert into identity_audit_log
                            (id, tenant_id, actor_type, actor_id, on_behalf_of, action, target_type, target_id,
                             before_state, after_state, reason, correlation_id, occurred_at)
                        values (:id, :tenantId, :actorType, :actorId, :onBehalfOf, :action, :targetType, :targetId,
                                cast(:before as jsonb), cast(:after as jsonb), :reason, :correlationId,
                                :occurredAt)
                        """)
                .param("id", UuidV7.next(clock))
                .param("tenantId", TenantContext.requireTenantId())
                .param("actorType", actorType)
                .param("actorId", actorId)
                .param("onBehalfOf", onBehalfOf)
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
}
