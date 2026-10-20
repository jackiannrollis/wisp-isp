package com.corebalt.isp.network.application;

import com.corebalt.isp.network.api.NetworkAccessChanged;
import com.corebalt.isp.network.api.OpenSessionDto;
import com.corebalt.isp.network.api.RouterSessionsDto;
import com.corebalt.isp.network.api.SessionReauthorizationFailed;
import com.corebalt.isp.network.api.SessionReauthorized;
import com.corebalt.isp.shared.tenant.TenantContext;
import com.corebalt.isp.shared.web.NotFoundException;
import com.corebalt.isp.shared.web.PageResponse;
import com.corebalt.isp.subscriptions.api.SubscriptionDirectory;
import java.sql.Array;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Session read models in the API: every open session of the tenant, a router's sessions (radacct beside
 * the worker's /ppp/active snapshot) and, per line, the last CoA or Disconnect outcome with what the portal's pay page needs to say
 * "Internet restored" (docs/06 Restricted mode).
 */
@Service
public class SessionViews {

    /** The last CoA outcome of a line, and whether its access is restored after {@code since}. */
    public record RestoreStatus(String accessProfile, Instant lastCoaAt, String lastCoaAction, String lastCoaResult,
                                long sessionsFromBefore, boolean restored) {}

    /** API sort keys and the columns they order by; PageRequests refuses any other key. */
    public static final Map<String, String> SESSION_SORT = Map.of(
            "username", "a.username", "startedAt", "a.acctstarttime", "lastHeardAt", "last_heard");

    private static final String OPEN_WHERE = """
             where a.acctstoptime is null
               and (cast(:routerId as uuid) is null or a.router_id = :routerId)
               and (cast(:q as text) is null or a.username ilike cast(:q as text) || '%')
            """;

    private final JdbcClient jdbc;
    private final Clock clock;
    private final SubscriptionDirectory lines;

    SessionViews(final JdbcClient jdbc, final Clock clock, final SubscriptionDirectory lines) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.lines = lines;
    }

    /** Open sessions of the current tenant (RLS), optionally on one router or by username prefix. */
    @Transactional(readOnly = true)
    public PageResponse<OpenSessionDto> openSessions(final UUID routerId, final String q, final Pageable page) {
        final String order = page.getSort().stream()
                .map(o -> SESSION_SORT.get(o.getProperty()) + (o.getDirection() == Sort.Direction.DESC ? " desc" : " asc"))
                .reduce((a, b) -> a + ", " + b)
                .orElse("a.username asc");
        // A username prefix matches literally: % and _ in it are not wildcards.
        final String prefix = q == null ? null : q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        final Map<UUID, Optional<UUID>> customers = new HashMap<>();
        final List<OpenSessionDto> items = jdbc.sql("""
                        select a.username, a.acctsessionid, host(a.framedipaddress), a.callingstationid, a.acctstarttime,
                               coalesce(a.acctupdatetime, a.acctstarttime) as last_heard, a.router_id, r.name, a.subscription_id,
                               case when r.ppp_active_at is null then null else a.username = any(r.ppp_active_users) end
                        from radacct a left join network_router r on r.id = a.router_id
                        """ + OPEN_WHERE + " order by " + order + ", a.acctuniqueid limit :limit offset :offset")
                .param("routerId", routerId)
                .param("q", prefix)
                .param("limit", page.getPageSize())
                .param("offset", page.getOffset())
                .query((rs, n) -> {
                    final UUID subscriptionId = rs.getObject(9, UUID.class);
                    final UUID customerId = subscriptionId == null ? null
                            : customers.computeIfAbsent(subscriptionId,
                                    id -> lines.line(id).map(SubscriptionDirectory.LineInfo::customerId)).orElse(null);
                    return new OpenSessionDto(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                            rs.getTimestamp(5).toInstant(), rs.getTimestamp(6).toInstant(), rs.getObject(7, UUID.class),
                            rs.getString(8), subscriptionId, customerId, (Boolean) rs.getObject(10));
                })
                .list();
        final long total = jdbc.sql("select count(*) from radacct a" + OPEN_WHERE)
                .param("routerId", routerId)
                .param("q", prefix)
                .query(Long.class)
                .single();
        return PageResponse.of(new PageImpl<>(items, page, total), s -> s);
    }

    @Transactional(readOnly = true)
    public RouterSessionsDto routerSessions(final UUID routerId) {
        final Optional<RouterSessionsDto.Snapshot> snapshot = jdbc.sql("""
                        select ppp_active_count, ppp_active_users, ppp_active_at from network_router where id = :id
                        """)
                .param("id", routerId)
                .query((rs, n) -> rs.getTimestamp(3) == null ? Optional.<RouterSessionsDto.Snapshot>empty()
                        : Optional.of(new RouterSessionsDto.Snapshot(rs.getInt(1), strings(rs.getArray(2)),
                                rs.getTimestamp(3).toInstant(),
                                Duration.between(rs.getTimestamp(3).toInstant(), clock.instant()).toSeconds())))
                .optional()
                .orElseThrow(() -> new NotFoundException("Router"));
        final List<RouterSessionsDto.OpenSession> radius = jdbc.sql("""
                        select username, acctsessionid, host(framedipaddress), acctstarttime, coalesce(acctupdatetime, acctstarttime)
                        from radacct
                        where router_id = :id and acctstoptime is null
                        order by username, acctstarttime
                        """)
                .param("id", routerId)
                .query((rs, n) -> new RouterSessionsDto.OpenSession(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getTimestamp(4).toInstant(), rs.getTimestamp(5).toInstant()))
                .list();
        final TreeSet<String> inRadius = new TreeSet<>(radius.stream().map(RouterSessionsDto.OpenSession::username).toList());
        final TreeSet<String> onRouter = new TreeSet<>(snapshot.map(RouterSessionsDto.Snapshot::usernames).orElse(List.of()));
        final List<String> onlyInRadius = snapshot.isEmpty() ? List.of()
                : inRadius.stream().filter(u -> !onRouter.contains(u)).toList();
        final List<String> onlyOnRouter = onRouter.stream().filter(u -> !inRadius.contains(u)).toList();
        return new RouterSessionsDto(radius, snapshot.orElse(null), onlyInRadius, onlyOnRouter);
    }

    /** Keeps the newest outcome per line; an older event arriving late changes nothing. In the consumer's transaction. */
    public void recordOutcome(final SessionReauthorized event) {
        record(event.subscriptionId(), event.occurredAt(), event.action(), event.sessionAlreadyGone() ? "SESSION_GONE" : "ACK",
                event.eventId());
    }

    public void recordOutcome(final SessionReauthorizationFailed event) {
        record(event.subscriptionId(), event.occurredAt(), event.action(), "FAILED", event.eventId());
    }

    private void record(final UUID subscriptionId, final Instant at, final NetworkAccessChanged.Action action,
                        final String result, final UUID eventId) {
        jdbc.sql("""
                        insert into network_line_coa (tenant_id, subscription_id, last_coa_at, last_coa_action, last_coa_result,
                                                      last_event_id)
                        values (:tenantId, :subscriptionId, :at, :action, :result, :eventId)
                        on conflict (tenant_id, subscription_id) do update
                            set last_coa_at = excluded.last_coa_at, last_coa_action = excluded.last_coa_action,
                                last_coa_result = excluded.last_coa_result, last_event_id = excluded.last_event_id
                            where network_line_coa.last_coa_at <= excluded.last_coa_at
                        """)
                .param("tenantId", TenantContext.requireTenantId())
                .param("subscriptionId", subscriptionId)
                .param("at", Timestamp.from(at))
                .param("action", action.name())
                .param("result", result)
                .param("eventId", eventId)
                .update();
    }

    /**
     * Restored after {@code since} (the payment): the line is FULL and every session that started before
     * {@code since}, which still carries the old profile, was changed by an acknowledged CoA or Disconnect
     * since then (or the router said it had already ended). A line with no such session needs no CoA: its
     * next login gets the new profile from the RADIUS view.
     */
    @Transactional(readOnly = true)
    public RestoreStatus restoreStatus(final UUID subscriptionId, final String accessProfile, final Instant since) {
        final long fromBefore = jdbc.sql("""
                        select count(*) from radacct
                        where subscription_id = :id and acctstoptime is null and acctstarttime < :since
                        """)
                .param("id", subscriptionId)
                .param("since", Timestamp.from(since))
                .query(Long.class)
                .single();
        record Last(Instant at, String action, String result) {}
        final Optional<Last> last = jdbc.sql("""
                        select last_coa_at, last_coa_action, last_coa_result from network_line_coa where subscription_id = :id
                        """)
                .param("id", subscriptionId)
                .query((rs, n) -> new Last(rs.getTimestamp(1).toInstant(), rs.getString(2), rs.getString(3)))
                .optional();
        final boolean changedSince = last.filter(l -> !l.at().isBefore(since))
                .filter(l -> !"FAILED".equals(l.result()))
                .isPresent();
        final boolean restored = "FULL".equals(accessProfile) && (fromBefore == 0 || changedSince);
        return new RestoreStatus(accessProfile, last.map(Last::at).orElse(null), last.map(Last::action).orElse(null),
                last.map(Last::result).orElse(null), fromBefore, restored);
    }

    private static List<String> strings(final Array array) throws SQLException {
        return array == null ? List.of() : Arrays.asList((String[]) array.getArray());
    }
}
