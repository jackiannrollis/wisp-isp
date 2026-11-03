package com.corebalt.isp.notifications.application;

import com.corebalt.isp.notifications.infrastructure.CelcomClient;
import com.corebalt.isp.shared.tenant.TenantIterator;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Queries accepted Celcom messages; never resends them or infers delivery from send acceptance. */
@Service
public class SmsDeliveryReports {
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final SmsSettings settings;
    private final CelcomClient provider;
    private final TenantIterator tenants;
    private final Clock clock;

    SmsDeliveryReports(final JdbcClient jdbc, final TransactionTemplate tx, final SmsSettings settings,
                       final CelcomClient provider, final TenantIterator tenants, final Clock clock) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.settings = settings;
        this.provider = provider;
        this.tenants = tenants;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${isp.sms.delivery-interval:60s}", initialDelayString = "${isp.sms.initial-delay:30s}")
    public void checkAll() { tenants.forEachTenant("sms-delivery", tenant -> checkDue()); }

    /** Short transaction to lease up to 50 checks, provider calls outside it, then tenant-scoped updates. */
    public int checkDue() {
        final var account = tx.execute(s -> settings.deliveryAccount());
        if (account == null || account.isEmpty()) { return 0; }
        final var now = clock.instant();
        final List<Claimed> claimed = tx.execute(s -> {
            final List<Claimed> rows = jdbc.sql("""
                            select id, provider_message_id from notifications_notification
                            where sms_provider = 'CELCOM_AFRICA' and status = 'SENT'
                              and provider_message_id is not null and sent_at > :since
                              and (delivery_check_at is null or delivery_check_at <= :now)
                            order by delivery_check_at nulls first, sent_at limit 50 for update skip locked
                            """)
                    .param("since", Timestamp.from(now.minus(Duration.ofHours(48))))
                    .param("now", Timestamp.from(now))
                    .query((rs, n) -> new Claimed(rs.getObject(1, UUID.class), rs.getString(2))).list();
            rows.forEach(row -> jdbc.sql("update notifications_notification set delivery_check_at = :next where id = :id")
                    .param("next", Timestamp.from(now.plus(Duration.ofMinutes(2)))).param("id", row.id()).update());
            return rows;
        });
        if (claimed == null) { return 0; }
        for (final var message : claimed) {
            try {
                final var report = provider.deliveryReport(account.get().partnerId(), account.get().apiKey(), message.messageId());
                tx.executeWithoutResult(s -> jdbc.sql("""
                                update notifications_notification
                                set provider_status = :providerStatus, status = :status,
                                    delivered_at = case when :delivered then :now else delivered_at end, updated_at = :now
                                where id = :id and provider_message_id = :messageId
                                  and sms_provider = 'CELCOM_AFRICA' and status = 'SENT'
                                """)
                        .param("providerStatus", report.status()).param("status", report.delivered() ? "DELIVERED" : "SENT")
                        .param("delivered", report.delivered()).param("now", Timestamp.from(clock.instant()))
                        .param("id", message.id()).param("messageId", message.messageId()).update());
            } catch (CelcomClient.TransportFailure ignored) {
                // Retry the lookup after its lease; no send attempt or secret-bearing diagnostic.
            }
        }
        return claimed.size();
    }

    private record Claimed(UUID id, String messageId) {}
}
