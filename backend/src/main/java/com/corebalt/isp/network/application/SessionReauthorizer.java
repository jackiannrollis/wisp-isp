package com.corebalt.isp.network.application;

import com.corebalt.isp.network.api.NetworkAccessChanged;
import com.corebalt.isp.network.api.SessionReauthorizationFailed;
import com.corebalt.isp.network.api.SessionReauthorized;
import com.corebalt.isp.network.infrastructure.RadiusCoaClient;
import com.corebalt.isp.network.infrastructure.RadiusPacket;
import com.corebalt.isp.shared.id.UuidV7;
import com.corebalt.isp.shared.outbox.DomainEvent;
import com.corebalt.isp.shared.tenant.TenantContext;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Sends the CoA or Disconnect for one {@link NetworkAccessChanged} (N5), run by the tunnel worker only
 * (ADR 0011). The router's tunnel address and RADIUS secret are read before every attempt, never
 * cached, so a rotated secret or a moved router takes effect on the next request (ADR 0009). The
 * first attempt is followed by up to {@link #RETRIES} retries, waiting backoff × 1, 3 and 7 between
 * them (2, 6 and 14 s by default, about 30 s in all with the timeouts). A NAK other than "no such
 * session" is final: the router understood and refused, so repeating would not help.
 *
 * <p>Returns the outcome event; the caller publishes it in the consumer's transaction. Network I/O
 * happens outside any transaction, so a slow router holds no database connection.
 */
@Service
public class SessionReauthorizer {

    /** N5: three retries after the first attempt. */
    public static final int RETRIES = 3;
    private static final int[] BACKOFF_FACTORS = {1, 3, 7};

    private static final Logger log = LoggerFactory.getLogger(SessionReauthorizer.class);

    private record Target(String tunnelIp, byte[] secret) {}

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final RadiusCoaClient radius;
    private final Clock clock;
    private final Duration backoff;
    private final MeterRegistry meters;

    SessionReauthorizer(final JdbcClient jdbc, final TransactionTemplate tx, final RadiusCoaClient radius, final Clock clock,
                        @Value("${isp.worker.coa.backoff:2s}") final Duration backoff, final MeterRegistry meters) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.radius = radius;
        this.clock = clock;
        this.backoff = backoff;
        this.meters = meters;
    }

    /** For the current tenant (the event's); returns SessionReauthorized or SessionReauthorizationFailed. */
    public DomainEvent send(final NetworkAccessChanged change) {
        final int code = change.action() == NetworkAccessChanged.Action.COA
                ? RadiusPacket.COA_REQUEST
                : RadiusPacket.DISCONNECT_REQUEST;
        final List<RadiusPacket.Attribute> attributes = attributes(change);
        String reason = null;
        int attempts = 0;
        while (attempts <= RETRIES) {
            if (attempts > 0) {
                pause(backoff.multipliedBy(BACKOFF_FACTORS[attempts - 1]));
            }
            attempts++;
            final Optional<Target> target = target(change);
            if (target.isEmpty()) {
                reason = "router has no tunnel address or RADIUS secret";
                break;
            }
            try {
                final RadiusCoaClient.Answer answer =
                        radius.send(target.get().tunnelIp(), target.get().secret(), code, attributes);
                if (answer.ack()) {
                    return reauthorized(change, false);
                }
                if (Integer.valueOf(RadiusPacket.SESSION_CONTEXT_NOT_FOUND).equals(answer.errorCause())) {
                    return reauthorized(change, true);
                }
                reason = "router refused (NAK, Error-Cause " + answer.errorCause() + ")";
                break;
            } catch (RadiusCoaClient.NoAnswer e) {
                reason = e.getMessage();
            }
        }
        log.warn("{} for line {} on router {} failed after {} attempt(s): {}", change.action(), change.subscriptionId(),
                change.routerId(), attempts, reason);
        // N5: a failure after the retries is alerted (infra/monitoring/rules/business.yml, CorebaltCoaFailed).
        Counter.builder("isp.network.coa.failed").description("CoA or Disconnect requests given up after the retries (N5)")
                .tag("action", change.action().name()).register(meters).increment();
        return new SessionReauthorizationFailed(UuidV7.next(clock), TenantContext.requireTenantId(), clock.instant(),
                change.subscriptionId(), change.routerId(), change.acctSessionId(), change.action(), attempts, reason);
    }

    private SessionReauthorized reauthorized(final NetworkAccessChanged change, final boolean alreadyGone) {
        log.info("{} for line {} on router {} {}", change.action(), change.subscriptionId(), change.routerId(),
                alreadyGone ? "found the session already ended" : "acknowledged");
        return new SessionReauthorized(UuidV7.next(clock), TenantContext.requireTenantId(), clock.instant(),
                change.subscriptionId(), change.routerId(), change.acctSessionId(), change.action(), alreadyGone);
    }

    /** The session is named by User-Name and Acct-Session-Id, so a stale request cannot drop a newer session. */
    private static List<RadiusPacket.Attribute> attributes(final NetworkAccessChanged change) {
        final List<RadiusPacket.Attribute> attributes = new ArrayList<>();
        attributes.add(RadiusPacket.Attribute.string(RadiusPacket.USER_NAME, change.username()));
        attributes.add(RadiusPacket.Attribute.string(RadiusPacket.ACCT_SESSION_ID, change.acctSessionId()));
        if (change.action() == NetworkAccessChanged.Action.COA) {
            attributes.add(RadiusPacket.Attribute.vendorString(
                    RadiusPacket.MIKROTIK_VENDOR, RadiusPacket.MIKROTIK_RATE_LIMIT, change.rateLimit()));
        }
        return attributes;
    }

    /**
     * The router's current address and secret. radius_client_v3 runs with its owner's rights, so row-level
     * security does not filter it: the tenant predicate is what keeps this to the event's tenant (T1, T3).
     */
    private Optional<Target> target(final NetworkAccessChanged change) {
        final UUID tenantId = TenantContext.requireTenantId();
        return tx.execute(s -> jdbc.sql("""
                        select c.nasname, c.secret
                        from network_router r
                        join radius_client_v3 c on c.tenant_id = r.tenant_id and c.nasname = host(r.tunnel_ip_a)
                        where r.id = :routerId and r.tenant_id = :tenantId and c.tenant_id = :tenantId
                        """)
                .param("routerId", change.routerId())
                .param("tenantId", tenantId)
                .query((rs, n) -> new Target(rs.getString(1), rs.getString(2).getBytes(StandardCharsets.UTF_8)))
                .optional());
    }

    private static void pause(final Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting to retry", e);
        }
    }
}
