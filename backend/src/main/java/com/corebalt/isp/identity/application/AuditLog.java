package com.corebalt.isp.identity.application;

import com.corebalt.isp.shared.web.PageResponse;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.simple.JdbcClient.StatementSpec;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The tenant's audit log (A1), newest first, filtered by action prefix, target, actor and period.
 * Rows written by Corebalt support during an impersonation show as such, on behalf of the staff
 * member.
 */
@Service
public class AuditLog {

    public record AuditRow(UUID id, String actorType, UUID actorId, String actorName, UUID onBehalfOf, String action,
                           String targetType, UUID targetId, String before, String after, String reason,
                           String correlationId, Instant occurredAt) {}

    public record Filter(String action, String targetType, UUID targetId, UUID actorId, Instant from, Instant to) {}

    private static final String WHERE = """
             where (cast(:action as text) is null or a.action like cast(:action as text) || '%')
               and (cast(:targetType as text) is null or a.target_type = :targetType)
               and (cast(:targetId as uuid) is null or a.target_id = :targetId)
               and (cast(:actorId as uuid) is null or a.actor_id = :actorId or a.on_behalf_of = :actorId)
               and (cast(:from as timestamptz) is null or a.occurred_at >= :from)
               and (cast(:to as timestamptz) is null or a.occurred_at < :to)
            """;

    private final JdbcClient jdbc;

    AuditLog(final JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public PageResponse<AuditRow> list(final Filter filter, final Pageable page) {
        final long total = bind(jdbc.sql("select count(*) from identity_audit_log a" + WHERE), filter).query(Long.class).single();
        final String direction = page.getSort().stream().anyMatch(o -> o.isAscending()) ? "asc" : "desc";
        final List<AuditRow> items = bind(jdbc.sql("""
                        select a.id, a.actor_type, a.actor_id,
                               case when a.actor_type = 'STAFF' then s.display_name
                                    when a.actor_type = 'PLATFORM' then 'Corebalt support' end,
                               a.on_behalf_of, a.action, a.target_type, a.target_id, a.before_state::text, a.after_state::text,
                               a.reason, a.correlation_id, a.occurred_at
                        from identity_audit_log a left join identity_staff_user s on s.id = a.actor_id
                        """ + WHERE + " order by a.occurred_at " + direction + ", a.id " + direction
                        + " limit :limit offset :offset"), filter)
                .param("limit", page.getPageSize())
                .param("offset", page.getOffset())
                .query((rs, n) -> new AuditRow(rs.getObject(1, UUID.class), rs.getString(2), rs.getObject(3, UUID.class),
                        rs.getString(4), rs.getObject(5, UUID.class), rs.getString(6), rs.getString(7), rs.getObject(8, UUID.class),
                        rs.getString(9), rs.getString(10), rs.getString(11), rs.getString(12), rs.getTimestamp(13).toInstant()))
                .list();
        return new PageResponse<>(items, page.getPageNumber(), page.getPageSize(), total);
    }

    private static StatementSpec bind(final StatementSpec spec, final Filter filter) {
        return spec.param("action", filter.action())
                .param("targetType", filter.targetType())
                .param("targetId", filter.targetId())
                .param("actorId", filter.actorId())
                .param("from", filter.from() == null ? null : Timestamp.from(filter.from()))
                .param("to", filter.to() == null ? null : Timestamp.from(filter.to()));
    }
}
