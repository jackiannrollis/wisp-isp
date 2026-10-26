package com.corebalt.isp.network.application;

import com.corebalt.isp.network.api.LineDisconnectedByStaff;
import com.corebalt.isp.shared.audit.AuditEntry;
import com.corebalt.isp.shared.audit.AuditTrail;
import com.corebalt.isp.shared.id.UuidV7;
import com.corebalt.isp.shared.outbox.OutboxPublisher;
import com.corebalt.isp.shared.security.CurrentActor;
import com.corebalt.isp.shared.tenant.TenantContext;
import com.corebalt.isp.shared.web.ConflictException;
import com.corebalt.isp.shared.web.NotFoundException;
import com.corebalt.isp.subscriptions.api.SubscriptionDirectory;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * "Disconnect sessions" on a line (ADR 0018): a staff member asks every live session of the line to
 * redial, with a reason. Before this action existed staff could only do it on the router, where the
 * platform saw nothing; now it is audited (A1) and published, so Gate 1 counts it as a manual reconnect
 * when it follows a payment.
 */
@Service
public class StaffDisconnects {

    public record Result(UUID subscriptionId, int sessions) {}

    private final AccessChanges changes;
    private final SubscriptionDirectory lines;
    private final AuditTrail audit;
    private final OutboxPublisher outbox;
    private final Clock clock;

    StaffDisconnects(final AccessChanges changes, final SubscriptionDirectory lines, final AuditTrail audit,
                     final OutboxPublisher outbox, final Clock clock) {
        this.changes = changes;
        this.lines = lines;
        this.audit = audit;
        this.outbox = outbox;
        this.clock = clock;
    }

    @Transactional
    public Result disconnect(final UUID subscriptionId, final String reason) {
        final SubscriptionDirectory.LineInfo line =
                lines.line(subscriptionId).orElseThrow(() -> new NotFoundException("Subscription"));
        final int sessions = changes.disconnectByStaff(subscriptionId);
        if (sessions == 0) {
            throw new ConflictException("Line " + line.pppoeUsername() + " has no live session to disconnect");
        }
        final Instant now = clock.instant();
        audit.record(new AuditEntry("network.sessions_disconnected", "subscription", subscriptionId, null,
                Map.of("sessions", sessions), reason));
        outbox.publish(new LineDisconnectedByStaff(UuidV7.next(clock), TenantContext.requireTenantId(), now, subscriptionId,
                line.customerId(), CurrentActor.staffId().orElse(null), CurrentActor.actingPlatformAdmin().orElse(null),
                reason, sessions));
        return new Result(subscriptionId, sessions);
    }
}
