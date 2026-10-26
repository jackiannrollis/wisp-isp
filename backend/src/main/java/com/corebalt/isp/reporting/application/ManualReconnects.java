package com.corebalt.isp.reporting.application;

import com.corebalt.isp.network.api.LineDisconnectedByStaff;
import com.corebalt.isp.payments.api.MatchedPayments;
import com.corebalt.isp.reporting.api.PaidOfflineReportDto;
import com.corebalt.isp.shared.audit.AuditEntry;
import com.corebalt.isp.shared.audit.AuditTrail;
import com.corebalt.isp.shared.id.UuidV7;
import com.corebalt.isp.shared.security.CurrentActor;
import com.corebalt.isp.shared.tenant.TenantContext;
import com.corebalt.isp.shared.web.ConflictException;
import com.corebalt.isp.shared.web.NotFoundException;
import com.corebalt.isp.subscriptions.api.SubscriptionDirectory;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manual reconnects for Gate 1 (docs/09 "no manual reconnects", ADR 0018), recorded directly rather than
 * guessed from alerts: a staff member's "Disconnect sessions" within {@link #AFTER_PAYMENT} after the
 * customer's latest payment, and support's "Paid but still offline" report on a line, linked to that
 * payment whenever it was. Both keep the actor (and the platform admin when Corebalt support acted) and a
 * reason. Current tenant only (T1).
 */
@Service
public class ManualReconnects {

    /** A staff disconnect this soon after a payment means the payment did not bring the customer back. */
    static final Duration AFTER_PAYMENT = Duration.ofHours(2);

    private final JdbcClient jdbc;
    private final SubscriptionDirectory lines;
    private final MatchedPayments payments;
    private final AuditTrail audit;
    private final Clock clock;

    ManualReconnects(final JdbcClient jdbc, final SubscriptionDirectory lines, final MatchedPayments payments,
                     final AuditTrail audit, final Clock clock) {
        this.jdbc = jdbc;
        this.lines = lines;
        this.payments = payments;
        this.audit = audit;
        this.clock = clock;
    }

    /** From the event, once (E2); nothing when the customer's latest payment is older than two hours, or none. */
    @Transactional
    public void recordStaffDisconnect(final LineDisconnectedByStaff event) {
        final Optional<MatchedPayments.Matched> payment = payments.latestFor(event.customerId(), event.occurredAt());
        if (payment.isEmpty() || payment.get().matchedAt().isBefore(event.occurredAt().minus(AFTER_PAYMENT))) {
            return;
        }
        insert(event.eventId(), "STAFF_DISCONNECT", event.subscriptionId(), payment.get(), event.occurredAt(),
                event.staffId(), event.actingPlatformAdminId(), event.reason());
    }

    /** Support's "Paid but still offline": 404 for an unknown line, 409 when its customer has never paid. */
    @Transactional
    public PaidOfflineReportDto reportPaidOffline(final UUID subscriptionId, final String note) {
        final SubscriptionDirectory.LineInfo line =
                lines.line(subscriptionId).orElseThrow(() -> new NotFoundException("Subscription"));
        final Instant now = clock.instant();
        final MatchedPayments.Matched payment = payments.latestFor(line.customerId(), now).orElseThrow(() ->
                new ConflictException("The customer of " + line.pppoeUsername() + " has no payment to link the report to"));
        final UUID id = UuidV7.next(clock);
        insert(id, "PAID_OFFLINE", subscriptionId, payment, now, CurrentActor.staffId().orElse(null),
                CurrentActor.actingPlatformAdmin().orElse(null), note);
        final Map<String, Object> after = new LinkedHashMap<>();
        after.put("paymentId", payment.paymentId().toString());
        after.put("paymentMatchedAt", payment.matchedAt().toString());
        audit.record(new AuditEntry("reporting.paid_offline_reported", "subscription", subscriptionId, null, after, note));
        return new PaidOfflineReportDto(id, subscriptionId, payment.paymentId(), payment.matchedAt(), now);
    }

    private void insert(final UUID id, final String kind, final UUID subscriptionId, final MatchedPayments.Matched payment,
                        final Instant at, final UUID staffId, final UUID platformAdminId, final String reason) {
        jdbc.sql("""
                        insert into reporting_manual_reconnect (tenant_id, id, kind, subscription_id, payment_id, payment_matched_at,
                                                                occurred_at, staff_id, acting_platform_admin_id, reason)
                        values (:tenantId, :id, :kind, :subscriptionId, :paymentId, :paymentMatchedAt, :at, :staffId, :platformAdminId, :reason)
                        on conflict do nothing
                        """)
                .param("tenantId", TenantContext.requireTenantId())
                .param("id", id)
                .param("kind", kind)
                .param("subscriptionId", subscriptionId)
                .param("paymentId", payment.paymentId())
                .param("paymentMatchedAt", Timestamp.from(payment.matchedAt()))
                .param("at", Timestamp.from(at))
                .param("staffId", staffId)
                .param("platformAdminId", platformAdminId)
                .param("reason", reason)
                .update();
    }
}
