package com.corebalt.isp.network.application;

import com.corebalt.isp.shared.tenant.TenantContext;
import com.corebalt.isp.shared.tenant.TenantIterator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Schedules {@link Accounting} in the API (not in the tunnel worker): partitions at start and every
 * six hours, stale sessions and "last heard" every 5 minutes, the usage roll-up every 15 minutes.
 * Disabled in tests, which call {@link Accounting} directly.
 */
@Component
@ConditionalOnProperty(name = "isp.network.jobs.enabled", havingValue = "true", matchIfMissing = true)
class AccountingJobs {

    private static final Logger log = LoggerFactory.getLogger(AccountingJobs.class);

    private final Accounting accounting;
    private final TenantIterator tenants;

    AccountingJobs(final Accounting accounting, final TenantIterator tenants) {
        this.accounting = accounting;
        this.tenants = tenants;
    }

    @Scheduled(fixedDelayString = "${isp.network.jobs.partitions-interval:6h}", initialDelayString = "${isp.network.jobs.initial-delay:1m}")
    void partitions() {
        final int created = TenantContext.callAsPlatform("radacct-partitions", accounting::ensurePartitions);
        final int dropped = TenantContext.callAsPlatform("radacct-partitions", accounting::dropExpiredPartitions);
        if (created > 0 || dropped > 0) {
            log.info("radacct partitions: {} created, {} dropped", created, dropped);
        }
    }

    @Scheduled(fixedDelayString = "${isp.network.jobs.stale-interval:5m}", initialDelayString = "${isp.network.jobs.initial-delay:1m}")
    void staleSessions() {
        tenants.forEachTenant("radacct-stale", tenantId -> {
            final int closed = accounting.closeStaleSessions();
            if (closed > 0) {
                log.info("Closed {} stale sessions", closed);
            }
            accounting.recordLastHeard();
        });
    }

    @Scheduled(fixedDelayString = "${isp.network.jobs.usage-interval:15m}", initialDelayString = "${isp.network.jobs.initial-delay:1m}")
    void usage() {
        tenants.forEachTenant("usage-roll-up", tenantId -> accounting.rollUpUsage());
    }
}
