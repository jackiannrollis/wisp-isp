package com.corebalt.isp.subscriptions.application;

import com.corebalt.isp.shared.tenant.TenantIterator;
import com.corebalt.isp.subscriptions.domain.LifecycleViolation;
import com.corebalt.isp.subscriptions.domain.SubscriptionState;
import com.corebalt.isp.subscriptions.infrastructure.SubscriptionRepository;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The hourly subscription job (docs/04 Enforcement, 4): records resumes the RADIUS view has already
 * applied at pause_until, then raises due reminders, tenant by tenant through
 * {@link TenantIterator}. Access never waits for it (E4). Disabled in tests, which call
 * {@link #runForCurrentTenant()} directly.
 */
@Component
@ConditionalOnProperty(name = "isp.subscriptions.jobs.enabled", havingValue = "true", matchIfMissing = true)
class SubscriptionJobs {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionJobs.class);

    private final TenantIterator tenants;
    private final SubscriptionRepository lines;
    private final LifecycleService lifecycle;
    private final ReminderService reminders;
    private final TransactionTemplate tx;
    private final Clock clock;

    SubscriptionJobs(
            final TenantIterator tenants,
            final SubscriptionRepository lines,
            final LifecycleService lifecycle,
            final ReminderService reminders,
            final TransactionTemplate tx,
            final Clock clock) {
        this.tenants = tenants;
        this.lines = lines;
        this.lifecycle = lifecycle;
        this.reminders = reminders;
        this.tx = tx;
        this.clock = clock;
    }

    @Scheduled(
            fixedDelayString = "${isp.subscriptions.jobs.interval:1h}",
            initialDelayString = "${isp.subscriptions.jobs.initial-delay:2m}")
    void run() {
        tenants.forEachTenant("subscription-jobs", tenantId -> runForCurrentTenant());
    }

    void runForCurrentTenant() {
        final List<UUID> pauseOver =
                tx.execute(s -> lines.findIdsPausedUntilBefore(SubscriptionState.PAUSED, clock.instant()));
        for (final UUID id : pauseOver) {
            try {
                lifecycle.recordAutoResume(id);
            } catch (LifecycleViolation e) {
                // Staff resumed or suspended it since the query; nothing to record.
                log.info("Auto-resume of {} skipped: {}", id, e.getMessage());
            }
        }
        final int raised = reminders.raiseDue();
        if (raised > 0 || !pauseOver.isEmpty()) {
            log.info("Subscription job: {} resumes recorded, {} reminders raised", pauseOver.size(), raised);
        }
    }
}
