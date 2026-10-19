package com.corebalt.isp.network.application;

import com.corebalt.isp.network.api.RouterOffline;
import com.corebalt.isp.network.api.RouterOnline;
import com.corebalt.isp.network.infrastructure.RouterOsClient;
import com.corebalt.isp.shared.id.UuidV7;
import com.corebalt.isp.shared.outbox.OutboxPublisher;
import com.corebalt.isp.shared.tenant.TenantContext;
import com.corebalt.isp.tenancy.api.SecretStore;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Router liveness (docs/06 Monitoring), run by the tunnel worker only (ADR 0011): every monitored
 * router of the current tenant is asked for {@code /system/resource} over its tunnel. An answer makes
 * it ONLINE; {@link #MISSES_FOR_OFFLINE} misses in a row make it OFFLINE and publish
 * {@link RouterOffline}; the first answer after that publishes {@link RouterOnline}. The REST
 * password is decrypted only for the call and never logged (T4, A3). Uses only the columns and
 * tables the isp_worker role is granted.
 */
@Service
public class RouterPoller {

    public static final int MISSES_FOR_OFFLINE = 3;
    /** SecretStore purpose, with the router ID: a ciphertext cannot be moved to another router. */
    public static final String REST_PURPOSE = "router-rest:";

    private static final Logger log = LoggerFactory.getLogger(RouterPoller.class);

    record Target(UUID id, String name, String tunnelIp, String username, byte[] password, String status, int misses,
                  Instant offlineSince) {}

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final SecretStore secrets;
    private final RouterOsClient routerOs;
    private final OutboxPublisher outbox;
    private final Clock clock;

    RouterPoller(final JdbcClient jdbc, final TransactionTemplate tx, final SecretStore secrets, final RouterOsClient routerOs,
                 final OutboxPublisher outbox, final Clock clock) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.secrets = secrets;
        this.routerOs = routerOs;
        this.outbox = outbox;
        this.clock = clock;
    }

    /** Polls the current tenant's monitored routers in parallel; returns how many answered. */
    public int pollCurrentTenant() {
        final List<Target> targets = tx.execute(s -> targets(null));
        if (targets == null || targets.isEmpty()) {
            return 0;
        }
        final UUID tenantId = TenantContext.requireTenantId();
        int online = 0;
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            final List<Future<Boolean>> results = targets.stream()
                    .map(target -> pool.submit(() -> TenantContext.callAsTenant(tenantId, () -> poll(target))))
                    .toList();
            for (final Future<Boolean> result : results) {
                try {
                    online += result.get() ? 1 : 0;
                } catch (Exception e) {
                    log.error("Router poll failed: {}", e.getClass().getSimpleName());
                }
            }
        }
        return online;
    }

    /** One router of the current tenant (RouterPollRequested); false when it did not answer or is not monitored. */
    public boolean pollOne(final UUID routerId) {
        final List<Target> targets = tx.execute(s -> targets(routerId));
        return targets != null && !targets.isEmpty() && poll(targets.getFirst());
    }

    private List<Target> targets(final UUID only) {
        return jdbc.sql("""
                        select id, name, host(tunnel_ip_a), rest_username, rest_password_enc, status, poll_misses, offline_since
                        from network_router
                        where rest_password_enc is not null and (cast(:only as uuid) is null or id = :only)
                        order by name
                        """)
                .param("only", only)
                .query((rs, n) -> new Target(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getBytes(5), rs.getString(6), rs.getInt(7),
                        rs.getTimestamp(8) == null ? null : rs.getTimestamp(8).toInstant()))
                .list();
    }

    private boolean poll(final Target target) {
        final String password = tx.execute(s -> secrets.decrypt(REST_PURPOSE + target.id(), target.password()));
        RouterOsClient.Resource resource = null;
        List<String> sessions = null;
        String failure = null;
        try {
            resource = routerOs.resource(target.tunnelIp(), target.username(), password);
            sessions = pppActive(target, password);
        } catch (RouterOsClient.PollFailure e) {
            failure = e.getMessage();
        }
        final RouterOsClient.Resource answer = resource;
        final List<String> snapshot = sessions;
        final String error = failure;
        tx.executeWithoutResult(s -> {
            if (answer != null) {
                answered(target, answer);
                if (snapshot != null) {
                    snapshot(target, snapshot);
                }
            } else {
                missed(target, error);
            }
        });
        return answer != null;
    }

    /**
     * The router's own session list, for the router page beside radacct (docs/06). A failure here does not
     * make the router missed: the resource call already proved it answers; the old snapshot just ages.
     */
    private List<String> pppActive(final Target target, final String password) {
        try {
            return routerOs.pppActive(target.tunnelIp(), target.username(), password);
        } catch (RouterOsClient.PollFailure e) {
            log.warn("Router {} answered but /ppp/active failed: {}", target.id(), e.getMessage());
            return null;
        }
    }

    private void snapshot(final Target target, final List<String> sessions) {
        jdbc.sql("""
                        update network_router
                        set ppp_active_count = :count, ppp_active_users = :users, ppp_active_at = :at
                        where id = :id
                        """)
                .param("count", sessions.size())
                .param("users", sessions.toArray(String[]::new))
                .param("at", Timestamp.from(clock.instant()))
                .param("id", target.id())
                .update();
    }

    private void answered(final Target target, final RouterOsClient.Resource resource) {
        final Instant now = clock.instant();
        jdbc.sql("""
                        update network_router
                        set status = 'ONLINE', poll_misses = 0, last_polled_at = :now, last_seen_at = :now, offline_since = null,
                            last_poll_error = null, routeros_version = :version, board_name = :board, uptime_seconds = :uptime,
                            cpu_load = :cpu, free_memory_bytes = :free, total_memory_bytes = :total
                        where id = :id
                        """)
                .param("now", Timestamp.from(now))
                .param("version", resource.version())
                .param("board", resource.boardName())
                .param("uptime", resource.uptimeSeconds())
                .param("cpu", resource.cpuLoad())
                .param("free", resource.freeMemory())
                .param("total", resource.totalMemory())
                .param("id", target.id())
                .update();
        if ("OFFLINE".equals(target.status())) {
            log.info("Router {} is back online", target.id());
            outbox.publish(new RouterOnline(UuidV7.next(clock), TenantContext.requireTenantId(), now, target.id(),
                    target.offlineSince()));
        }
    }

    private void missed(final Target target, final String error) {
        final Instant now = clock.instant();
        final int misses = target.misses() + 1;
        final boolean goesOffline = misses >= MISSES_FOR_OFFLINE && !"OFFLINE".equals(target.status());
        jdbc.sql("""
                        update network_router
                        set poll_misses = :misses, last_polled_at = :now, last_poll_error = :error,
                            status = case when :offline then 'OFFLINE' else status end,
                            offline_since = case when :offline then cast(:now as timestamptz) else offline_since end
                        where id = :id
                        """)
                .param("misses", misses)
                .param("now", Timestamp.from(now))
                .param("error", error)
                .param("offline", goesOffline)
                .param("id", target.id())
                .update();
        if (goesOffline) {
            log.warn("Router {} is offline after {} missed polls: {}", target.id(), misses, error);
            outbox.publish(new RouterOffline(UuidV7.next(clock), TenantContext.requireTenantId(), now, target.id(), now));
        }
    }
}
