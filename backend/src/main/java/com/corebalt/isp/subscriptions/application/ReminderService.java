package com.corebalt.isp.subscriptions.application;

import com.corebalt.isp.packages.api.PackageCatalog;
import com.corebalt.isp.shared.id.UuidV7;
import com.corebalt.isp.shared.outbox.OutboxPublisher;
import com.corebalt.isp.shared.tenant.TenantContext;
import com.corebalt.isp.subscriptions.api.SubscriptionReminderDue;
import com.corebalt.isp.subscriptions.api.WalletCharger;
import com.corebalt.isp.subscriptions.domain.ReminderSchedule;
import com.corebalt.isp.subscriptions.domain.Subscription;
import com.corebalt.isp.subscriptions.infrastructure.SubscriptionRepository;
import com.corebalt.isp.tenancy.api.TenantProfile;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Raises due reminders for the current tenant (docs/04 Reminders): one
 * {@link SubscriptionReminderDue} per dedupe key, ever (M1), recorded in
 * {@code subscriptions_reminder} in the same transaction as the outbox row. Sending, templates and
 * the sending window belong to the notifications module (M2). Access never depends on this (E4).
 */
@Service
public class ReminderService {

    private final SubscriptionRepository lines;
    private final TenantProfile tenantProfile;
    private final OutboxPublisher outbox;
    private final JdbcClient jdbc;
    private final PackageCatalog packages;
    private final WalletCharger wallet;
    private final Clock clock;

    ReminderService(
            final SubscriptionRepository lines,
            final TenantProfile tenantProfile,
            final OutboxPublisher outbox,
            final JdbcClient jdbc,
            final PackageCatalog packages,
            final WalletCharger wallet,
            final Clock clock) {
        this.lines = lines;
        this.tenantProfile = tenantProfile;
        this.outbox = outbox;
        this.jdbc = jdbc;
        this.packages = packages;
        this.wallet = wallet;
        this.clock = clock;
    }

    /** The wallet already holds the next cycle's price of a prepaid package. */
    private boolean renewsAutomatically(final Subscription line) {
        return packages.terms(line.packageForNextRenewal())
                .filter(PackageCatalog.PackageTerms::prepaid)
                .map(terms -> wallet.balance(line.getCustomerId(), terms.price().currency())
                        .isGreaterThanOrEqualTo(terms.price()))
                .orElse(false);
    }

    /** Returns how many reminders were raised. */
    @Transactional
    public int raiseDue() {
        final Instant now = clock.instant();
        final ZoneId zone = tenantProfile.currentPolicy().zone();
        int raised = 0;
        for (final Subscription line : lines.findActiveInReminderRange(now)) {
            final Optional<ReminderSchedule.Event> event =
                    ReminderSchedule.due(line.getExpiresAt(), line.getGraceDays(), zone, now);
            if (event.isPresent() && raise(line, event.get(), now)) {
                raised++;
            }
        }
        return raised;
    }

    private boolean raise(final Subscription line, final ReminderSchedule.Event event, final Instant now) {
        final String dedupeKey = ReminderSchedule.dedupeKey(event, line.getId(), line.getExpiresAt());
        final int inserted =
                jdbc.sql(
                                """
                                insert into subscriptions_reminder
                                    (id, tenant_id, subscription_id, event_key, expires_at, dedupe_key)
                                values (:id, :tenantId, :subscriptionId, :eventKey, :expiresAt, :dedupeKey)
                                on conflict (tenant_id, dedupe_key) do nothing
                                """)
                        .param("id", UuidV7.next(clock))
                        .param("tenantId", TenantContext.requireTenantId())
                        .param("subscriptionId", line.getId())
                        .param("eventKey", event.name())
                        .param("expiresAt", Timestamp.from(line.getExpiresAt()))
                        .param("dedupeKey", dedupeKey)
                        .update();
        if (inserted == 0) {
            return false;
        }
        outbox.publish(new SubscriptionReminderDue(
                UuidV7.next(clock),
                line.getTenantId(),
                now,
                line.getId(),
                line.getCustomerId(),
                event.name(),
                line.getExpiresAt(),
                dedupeKey,
                renewsAutomatically(line)));
        return true;
    }
}
