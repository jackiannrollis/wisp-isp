package com.corebalt.isp.notifications.application;

import com.corebalt.isp.notifications.api.CustomerNotified;
import com.corebalt.isp.notifications.domain.SendOutcome;
import com.corebalt.isp.notifications.infrastructure.AfricasTalkingClient;
import com.corebalt.isp.shared.id.UuidV7;
import com.corebalt.isp.shared.outbox.OutboxPublisher;
import com.corebalt.isp.shared.tenant.TenantContext;
import com.corebalt.isp.shared.tenant.TenantIterator;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Sends queued messages, tenant by tenant. Due messages are claimed with a short lease and
 * committed, the provider is called outside any transaction, then the outcome is recorded
 * ({@link SendOutcome}). Gauges for the platform: {@code isp.notifications.alerts.open} (open SMS
 * alerts across tenants) and {@code isp.notifications.dead} (dead-lettered messages).
 */
@Service
public class SmsSender {

    private static final Logger log = LoggerFactory.getLogger(SmsSender.class);
    static final Duration LEASE = Duration.ofMinutes(5);
    static final int BATCH = 50;

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final SmsSettings settings;
    private final AfricasTalkingClient provider;
    private final OutboxPublisher outbox;
    private final TenantIterator tenants;
    private final Clock clock;
    private final AtomicInteger openAlerts = new AtomicInteger();
    private final AtomicInteger dead = new AtomicInteger();

    SmsSender(
            final JdbcClient jdbc,
            final TransactionTemplate tx,
            final SmsSettings settings,
            final AfricasTalkingClient provider,
            final OutboxPublisher outbox,
            final TenantIterator tenants,
            final Clock clock,
            final MeterRegistry meters) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.settings = settings;
        this.provider = provider;
        this.outbox = outbox;
        this.tenants = tenants;
        this.clock = clock;
        meters.gauge("isp.notifications.alerts.open", openAlerts);
        meters.gauge("isp.notifications.dead", dead);
    }

    @Scheduled(fixedDelayString = "${isp.sms.send-interval:10s}", initialDelayString = "${isp.sms.initial-delay:30s}")
    public void sendAll() {
        final AtomicInteger alerts = new AtomicInteger();
        final AtomicInteger deadCount = new AtomicInteger();
        tenants.forEachTenant("sms-sender", tenantId -> {
            sendDue();
            alerts.addAndGet(tx.execute(s -> jdbc.sql("select count(*) from notifications_alert where cleared_at is null")
                    .query(Long.class).single()).intValue());
            deadCount.addAndGet(tx.execute(s -> jdbc.sql("select count(*) from notifications_notification where status = 'DEAD'")
                    .query(Long.class).single()).intValue());
        });
        openAlerts.set(alerts.get());
        dead.set(deadCount.get());
    }

    /** One batch for the current tenant; returns how many were attempted. */
    public int sendDue() {
        final Instant now = clock.instant();
        final List<Claimed> claimed = tx.execute(s -> {
            final List<Claimed> rows = jdbc.sql("""
                            select id, customer_id, recipient, body, template_key, attempts from notifications_notification
                            where status in ('QUEUED', 'QUEUED_RETRY') and next_attempt_at <= :now
                            order by next_attempt_at limit :batch
                            for update skip locked
                            """)
                    .param("now", Timestamp.from(now))
                    .param("batch", BATCH)
                    .query((rs, n) -> new Claimed(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                            rs.getString(4), rs.getString(5), rs.getInt(6)))
                    .list();
            rows.forEach(row -> jdbc.sql("update notifications_notification set next_attempt_at = :lease where id = :id")
                    .param("lease", Timestamp.from(now.plus(LEASE))).param("id", row.id()).update());
            return rows;
        });
        if (claimed == null || claimed.isEmpty()) {
            return 0;
        }
        final Optional<SmsSettings.Account> account = tx.execute(s -> settings.account());
        for (final Claimed message : claimed) {
            final SendOutcome outcome = account.isEmpty()
                    ? new SendOutcome.TenantProblem(null, "SMS is not configured for this tenant")
                    : send(account.get(), message);
            tx.executeWithoutResult(s -> record(message, outcome));
        }
        return claimed.size();
    }

    private SendOutcome send(final SmsSettings.Account account, final Claimed message) {
        try {
            final AfricasTalkingClient.Result result = provider.send(
                    account.sandbox(), account.username(), account.apiKey(), account.senderId(), message.recipient(), message.body());
            return SendOutcome.ofRecipient(result.statusCode(), result.status(), result.messageId());
        } catch (AfricasTalkingClient.TransportFailure e) {
            return SendOutcome.ofTransport(e.httpStatus(), e.getMessage());
        }
    }

    private void record(final Claimed message, final SendOutcome outcome) {
        final Instant now = clock.instant();
        switch (outcome) {
            case SendOutcome.Sent sent -> {
                update(message.id(), "SENT", now, message.attempts() + 1, sent.providerStatus(), null, sent.messageId(), now);
                // The provider took it: tenant-side SMS problems are over.
                jdbc.sql("""
                                update notifications_alert set cleared_at = :now
                                where cleared_at is null and kind in ('SMS_CREDIT_EXHAUSTED', 'SENDER_ID_INVALID', 'SMS_AUTH_FAILED')
                                """)
                        .param("now", Timestamp.from(now)).update();
                outbox.publish(new CustomerNotified(UuidV7.next(clock), TenantContext.requireTenantId(), now, message.id(),
                        message.customerId(), message.templateKey()));
            }
            case SendOutcome.Permanent permanent -> {
                update(message.id(), "FAILED", now, message.attempts() + 1, null, permanent.reason(), null, null);
                jdbc.sql("""
                                insert into notifications_phone_flag (tenant_id, phone, status, reason, flagged_at)
                                values (:tenantId, :phone, :status, :reason, :now)
                                on conflict (tenant_id, phone) do update set status = excluded.status, reason = excluded.reason,
                                    flagged_at = excluded.flagged_at
                                """)
                        .param("tenantId", TenantContext.requireTenantId())
                        .param("phone", message.recipient())
                        .param("status", permanent.flag())
                        .param("reason", permanent.reason())
                        .param("now", Timestamp.from(now))
                        .update();
            }
            case SendOutcome.TenantProblem problem -> {
                update(message.id(), "QUEUED_RETRY", now.plus(SendOutcome.TENANT_RETRY), message.attempts(), null,
                        problem.reason(), null, null);
                if (problem.alertKind() != null) {
                    raise(problem.alertKind(), problem.reason(), now);
                }
            }
            case SendOutcome.Transient failure -> {
                final int attempts = message.attempts() + 1;
                if (attempts >= SendOutcome.MAX_ATTEMPTS) {
                    update(message.id(), "DEAD", now, attempts, null, failure.reason(), null, null);
                    raise("SMS_DEAD_LETTERED", "a message failed " + attempts + " times: " + failure.reason(), now);
                    log.error("SMS {} dead-lettered after {} attempts: {}", message.id(), attempts, failure.reason());
                } else {
                    update(message.id(), "QUEUED_RETRY", now.plus(SendOutcome.backoff(attempts)), attempts, null,
                            failure.reason(), null, null);
                }
            }
        }
    }

    private void update(final UUID id, final String status, final Instant nextAttempt, final int attempts,
                        final String providerStatus, final String failure, final String messageId, final Instant sentAt) {
        jdbc.sql("""
                        update notifications_notification
                        set status = :status, next_attempt_at = :next, attempts = :attempts,
                            provider_status = coalesce(:providerStatus, provider_status), failure_reason = :failure,
                            provider_message_id = coalesce(:messageId, provider_message_id), sent_at = coalesce(:sentAt, sent_at),
                            updated_at = :now
                        where id = :id
                        """)
                .param("status", status)
                .param("next", Timestamp.from(nextAttempt))
                .param("attempts", attempts)
                .param("providerStatus", providerStatus)
                .param("failure", failure)
                .param("messageId", messageId)
                .param("sentAt", sentAt == null ? null : Timestamp.from(sentAt))
                .param("now", Timestamp.from(clock.instant()))
                .param("id", id)
                .update();
    }

    /** One open alert per kind; tenant staff see it, the platform gauge counts it. */
    private void raise(final String kind, final String detail, final Instant now) {
        final int inserted = jdbc.sql("""
                        insert into notifications_alert (id, tenant_id, kind, detail, raised_at)
                        values (:id, :tenantId, :kind, :detail, :now)
                        on conflict (tenant_id, kind) where cleared_at is null do nothing
                        """)
                .param("id", UuidV7.next(clock))
                .param("tenantId", TenantContext.requireTenantId())
                .param("kind", kind)
                .param("detail", detail)
                .param("now", Timestamp.from(now))
                .update();
        if (inserted == 1) {
            log.error("SMS alert {} for tenant {}: {}", kind, TenantContext.requireTenantId(), detail);
        }
    }

    /**
     * A delivery report (form fields id, status, failureReason): Success is DELIVERED, Failed and
     * Rejected are FAILED with the reason; Buffered, Sent and Submitted leave the message SENT. A
     * report for an unknown message ID is logged and dropped.
     */
    public void deliveryReport(final String messageId, final String status, final String failureReason) {
        tx.executeWithoutResult(s -> {
            final String newStatus = switch (status == null ? "" : status) {
                case "Success" -> "DELIVERED";
                case "Failed", "Rejected" -> "FAILED";
                default -> null;
            };
            final int updated = jdbc.sql("""
                            update notifications_notification
                            set provider_status = :providerStatus,
                                status = coalesce(:status, status),
                                failure_reason = case when :status = 'FAILED' then :reason else failure_reason end,
                                delivered_at = case when :status = 'DELIVERED' then :now else delivered_at end,
                                updated_at = :now
                            where provider_message_id = :messageId and status in ('SENT', 'DELIVERED', 'FAILED')
                            """)
                    .param("providerStatus", status)
                    .param("status", newStatus)
                    .param("reason", failureReason == null ? status : status + ": " + failureReason)
                    .param("now", Timestamp.from(clock.instant()))
                    .param("messageId", messageId)
                    .update();
            if (updated == 0) {
                log.info("Delivery report for an unknown message id; dropped");
            }
        });
    }

    private record Claimed(UUID id, UUID customerId, String recipient, String body, String templateKey, int attempts) {}
}
