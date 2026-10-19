package com.corebalt.isp.network.application;

import com.corebalt.isp.network.api.NetworkAccessChanged;
import com.corebalt.isp.network.api.NetworkAccessChanged.Action;
import com.corebalt.isp.network.domain.RateLimits;
import com.corebalt.isp.packages.api.PackageCatalog;
import com.corebalt.isp.packages.api.PackageCatalog.PackageSpeeds;
import com.corebalt.isp.shared.id.UuidV7;
import com.corebalt.isp.shared.outbox.OutboxPublisher;
import com.corebalt.isp.shared.tenant.TenantContext;
import com.corebalt.isp.subscriptions.api.SubscriptionDirectory;
import com.corebalt.isp.subscriptions.api.SubscriptionPackageChanged;
import com.corebalt.isp.subscriptions.api.SubscriptionRenewed;
import com.corebalt.isp.subscriptions.api.SubscriptionStateChanged;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Turns subscription changes into CoA or Disconnect requests for the line's live sessions (N5,
 * docs/06), in the API, in the consumer's transaction. The open sessions come from radacct, which
 * the tunnel worker cannot read; the worker only signs and sends what is published here (ADR 0011).
 * A line with no open session gets nothing: its next login gets the new profile from the view.
 *
 * <ul>
 *   <li>SubscriptionStateChanged: Disconnect (pause, resume, suspend, lift, terminate and activation
 *       change the pool, address list or access), except AUTO_RESUME_RECORDED (S7: the view already
 *       resumed the line, its fresh session must stay).
 *   <li>SubscriptionPackageChanged NOW: CoA with the new package's rate while the line is FULL, or its
 *       throttle rate while THROTTLED (S10); a RESTRICTED or rejected line keeps its fixed profile.
 *   <li>SubscriptionRenewed: Disconnect when the renewal changed the profile (RESTRICTED or THROTTLED
 *       back to FULL: pool or address list differ); nothing when it did not, since the session's
 *       Session-Timeout (S4) already ends it at the old boundary and the redial gets the new expiry.
 * </ul>
 */
@Service
public class AccessChanges {

    /** Sessions last at most a day (S4); older open rows are stale, not live. */
    private static final Duration LIVE_WINDOW = Duration.ofHours(48);

    private static final Logger log = LoggerFactory.getLogger(AccessChanges.class);

    record Session(UUID routerId, String username, String acctSessionId) {}

    private final JdbcClient jdbc;
    private final SubscriptionDirectory lines;
    private final PackageCatalog packages;
    private final OutboxPublisher outbox;
    private final Clock clock;

    AccessChanges(final JdbcClient jdbc, final SubscriptionDirectory lines, final PackageCatalog packages,
                  final OutboxPublisher outbox, final Clock clock) {
        this.jdbc = jdbc;
        this.lines = lines;
        this.packages = packages;
        this.outbox = outbox;
        this.clock = clock;
    }

    /** Returns how many sessions were asked to change. */
    public int stateChanged(final SubscriptionStateChanged event) {
        if (event.cause() == SubscriptionStateChanged.Cause.AUTO_RESUME_RECORDED) {
            return 0;
        }
        return request(event.subscriptionId(), Action.DISCONNECT, null,
                "state " + event.fromState() + " to " + event.toState());
    }

    public int renewed(final SubscriptionRenewed event) {
        if (event.previousExpiresAt() == null) {
            return 0; // first activation: SubscriptionStateChanged covers it, and a PENDING line has no session
        }
        final Optional<String> before = lines.accessProfile(event.subscriptionId(), event.occurredAt(), event.previousExpiresAt());
        final Optional<String> after = lines.accessProfile(event.subscriptionId(), event.occurredAt(), event.expiresAt());
        if (before.isEmpty() || before.equals(after)) {
            return 0;
        }
        return request(event.subscriptionId(), Action.DISCONNECT, null,
                "renewal " + before.get() + " to " + after.orElse("?"));
    }

    public int packageChanged(final SubscriptionPackageChanged event) {
        if (!"NOW".equals(event.timing())) {
            return 0;
        }
        final String profile = lines.accessProfile(event.subscriptionId(), clock.instant(), null).orElse("REJECT");
        final Optional<PackageSpeeds> speeds = packages.speeds(event.toPackageId());
        if (speeds.isEmpty()) {
            return 0;
        }
        final PackageSpeeds s = speeds.get();
        final String rate = switch (profile) {
            case "FULL" -> RateLimits.of(s.uploadKbps(), s.downloadKbps());
            case "THROTTLED" -> s.throttleUploadKbps() == null || s.throttleDownloadKbps() == null
                    ? RateLimits.of(s.uploadKbps(), s.downloadKbps())
                    : RateLimits.of(s.throttleUploadKbps(), s.throttleDownloadKbps());
            default -> null;
        };
        return rate == null ? 0 : request(event.subscriptionId(), Action.COA, rate, "package change");
    }

    private int request(final UUID subscriptionId, final Action action, final String rateLimit, final String reason) {
        final UUID tenantId = TenantContext.requireTenantId();
        final Instant now = clock.instant();
        final List<Session> sessions = jdbc.sql("""
                        select router_id, username, acctsessionid
                        from radacct
                        where tenant_id = :tenantId and subscription_id = :subscriptionId and acctstoptime is null
                          and router_id is not null and acctstarttime > :since
                        order by acctstarttime
                        """)
                .param("tenantId", tenantId)
                .param("subscriptionId", subscriptionId)
                .param("since", Timestamp.from(now.minus(LIVE_WINDOW)))
                .query((rs, n) -> new Session(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3)))
                .list();
        for (final Session session : sessions) {
            outbox.publish(new NetworkAccessChanged(UuidV7.next(clock), tenantId, now, subscriptionId, session.routerId(),
                    session.username(), session.acctSessionId(), action, rateLimit, reason));
        }
        if (!sessions.isEmpty()) {
            log.info("{} requested for {} session(s) of line {}: {}", action, sessions.size(), subscriptionId, reason);
        }
        return sessions.size();
    }
}
