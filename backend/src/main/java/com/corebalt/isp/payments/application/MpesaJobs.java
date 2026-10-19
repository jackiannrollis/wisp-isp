package com.corebalt.isp.payments.application;

import com.corebalt.isp.shared.tenant.TenantIterator;
import com.corebalt.isp.tenancy.api.TenantProfile;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * M-Pesa background work, per tenant: STK follow-up (P6, every 30 seconds), and the callback
 * silence alert (docs/05 Reconciliation): a tenant that received C2B confirmations in the last week
 * but none in the last 2 hours between 07:00 and 21:00 local time probably lost its URL
 * registration. The gauge {@code isp.mpesa.callbacks.silent_tenants} is what alerts.
 */
@Component
class MpesaJobs {

    private static final Logger log = LoggerFactory.getLogger(MpesaJobs.class);

    static final Duration SILENCE = Duration.ofHours(2);

    private final TenantIterator tenants;
    private final StkService stk;
    private final TenantProfile tenantProfile;
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final AtomicInteger silent = new AtomicInteger();

    MpesaJobs(
            final TenantIterator tenants,
            final StkService stk,
            final TenantProfile tenantProfile,
            final JdbcClient jdbc,
            final TransactionTemplate tx,
            final Clock clock,
            final MeterRegistry meters) {
        this.tenants = tenants;
        this.stk = stk;
        this.tenantProfile = tenantProfile;
        this.jdbc = jdbc;
        this.tx = tx;
        this.clock = clock;
        meters.gauge("isp.mpesa.callbacks.silent_tenants", silent);
    }

    @Scheduled(fixedDelayString = "${isp.mpesa.stk.follow-up-interval:30s}", initialDelayString = "${isp.mpesa.jobs.initial-delay:1m}")
    void followUpStk() {
        tenants.forEachTenant("mpesa-stk-follow-up", t -> stk.followUpPending());
    }

    @Scheduled(fixedDelayString = "${isp.mpesa.silence.interval:10m}", initialDelayString = "${isp.mpesa.jobs.initial-delay:1m}")
    void callbackSilence() {
        final AtomicInteger count = new AtomicInteger();
        tenants.forEachTenant("mpesa-callback-silence", t -> {
            if (Boolean.TRUE.equals(tx.execute(s -> isSilent()))) {
                count.incrementAndGet();
                log.error("No M-Pesa confirmations for tenant {} in {}; check the C2B URL registration", t, SILENCE);
            }
        });
        silent.set(count.get());
    }

    private boolean isSilent() {
        final Instant now = clock.instant();
        final LocalTime local = LocalTime.ofInstant(now, tenantProfile.currentPolicy().zone());
        if (local.isBefore(LocalTime.of(9, 0)) || local.isAfter(LocalTime.of(21, 0))) {
            // Two quiet hours that started before 07:00 are normal.
            return false;
        }
        final Long recent = jdbc.sql("select count(*) from payments_mpesa_callback where kind = 'C2B_CONFIRMATION' and received_at > :since")
                .param("since", Timestamp.from(now.minus(SILENCE)))
                .query(Long.class)
                .single();
        final Long week = jdbc.sql("select count(*) from payments_mpesa_callback where kind = 'C2B_CONFIRMATION' and received_at > :since")
                .param("since", Timestamp.from(now.minus(Duration.ofDays(7))))
                .query(Long.class)
                .single();
        return recent == 0 && week > 0;
    }
}
