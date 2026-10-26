package com.corebalt.isp.network.application;

import com.corebalt.isp.network.api.RouterDto;
import com.corebalt.isp.network.api.RouterRetireRequest;
import com.corebalt.isp.network.api.RouterRetired;
import com.corebalt.isp.network.domain.Router;
import com.corebalt.isp.network.infrastructure.RadiusCredentials;
import com.corebalt.isp.network.infrastructure.RouterRepository;
import com.corebalt.isp.shared.audit.AuditEntry;
import com.corebalt.isp.shared.audit.AuditTrail;
import com.corebalt.isp.shared.id.UuidV7;
import com.corebalt.isp.shared.outbox.OutboxPublisher;
import com.corebalt.isp.shared.tenant.TenantContext;
import com.corebalt.isp.shared.web.ConflictException;
import com.corebalt.isp.shared.web.InvalidRequestException;
import com.corebalt.isp.shared.web.NotFoundException;
import com.corebalt.isp.subscriptions.api.LineRouters;
import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Retires a router for good (N10, ADR 0015), in one transaction:
 *
 * <ol>
 *   <li>its lines that are not terminated move to the replacement, which is required when there are any
 *       (bookkeeping only: logins work through any router of the tenant, T3);
 *   <li>its REST login is cleared, so the tunnel worker stops polling it;
 *   <li>its RADIUS secret is revoked, so radius_client_v3 no longer lists it and FreeRADIUS stops
 *       answering it at the reload the host's routersync makes within a minute;
 *   <li>its open sessions are closed, so no CoA or Disconnect is ever aimed at it;
 *   <li>an audit row with the reason (A1) and {@link RouterRetired}, which clears its offline alert.
 * </ol>
 *
 * The host's routersync then removes its WireGuard peer from the hub, so a stolen router holding its key
 * can no longer reach the platform (N6). Lives apart from RouterService because subscriptions' line
 * service, which moves the lines, itself depends on RouterService through RouterDirectory.
 */
@Service
public class RouterRetirement {

    private final RouterRepository routers;
    private final RadiusCredentials credentials;
    private final Accounting accounting;
    private final LineRouters lines;
    private final JdbcClient jdbc;
    private final EntityManager entities;
    private final OutboxPublisher outbox;
    private final AuditTrail audit;
    private final Clock clock;

    RouterRetirement(final RouterRepository routers, final RadiusCredentials credentials, final Accounting accounting,
                     final LineRouters lines, final JdbcClient jdbc, final EntityManager entities,
                     final OutboxPublisher outbox, final AuditTrail audit, final Clock clock) {
        this.routers = routers;
        this.credentials = credentials;
        this.accounting = accounting;
        this.lines = lines;
        this.jdbc = jdbc;
        this.entities = entities;
        this.outbox = outbox;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public RouterDto retire(final UUID routerId, final RouterRetireRequest request) {
        final Router router = routers.findById(routerId).orElseThrow(() -> new NotFoundException("Router"));
        router.requireActive();
        final String reason = request.reason() == null ? "" : request.reason().strip();
        if (reason.isEmpty()) {
            throw InvalidRequestException.of("reason", "required");
        }
        final UUID replacementId = request.replacementRouterId();
        final long live = lines.liveLinesOn(routerId);
        if (replacementId != null && !replacementId.equals(routerId)) {
            final Router replacement = routers.findById(replacementId)
                    .orElseThrow(() -> InvalidRequestException.of("replacementRouterId", "no such router"));
            if (replacement.isRetired()) {
                throw InvalidRequestException.of("replacementRouterId", "is retired; choose an active router");
            }
        } else if (replacementId == null && live > 0) {
            throw new ConflictException("N10: router '" + router.getName() + "' still has " + live
                    + (live == 1 ? " line" : " lines") + "; choose the router that takes them over.");
        }
        final Instant now = clock.instant();
        router.retire(reason, replacementId, now);
        routers.saveAndFlush(router);

        final int moved = replacementId == null ? 0 : lines.moveLiveLines(routerId, replacementId);
        jdbc.sql("""
                        update network_router
                        set rest_username = null, rest_password_enc = null, rest_key_version = null, status = 'NOT_MONITORED',
                            poll_misses = 0, offline_since = null, last_poll_error = null, updated_at = :now
                        where id = :id
                        """)
                .param("now", Timestamp.from(now))
                .param("id", routerId)
                .update();
        credentials.revokeRouterSecret(routerId);
        final int closed = accounting.closeSessionsOf(routerId);

        final Map<String, Object> after = new LinkedHashMap<>();
        after.put("retiredAt", now.toString());
        after.put("replacementRouterId", replacementId == null ? null : replacementId.toString());
        after.put("linesMoved", moved);
        after.put("sessionsClosed", closed);
        audit.record(new AuditEntry("network.router_retired", "router", routerId, Map.of("name", router.getName()), after,
                reason));
        outbox.publish(new RouterRetired(UuidV7.next(clock), TenantContext.requireTenantId(), now, routerId, replacementId,
                moved));
        // Moving lines clears the persistence context, and the SQL above bypassed the entity: read it again.
        final Router current = routers.findById(routerId).orElseThrow(() -> new NotFoundException("Router"));
        entities.refresh(current);
        return RouterService.toDto(current);
    }
}
