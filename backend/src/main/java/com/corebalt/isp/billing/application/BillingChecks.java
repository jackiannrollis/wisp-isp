package com.corebalt.isp.billing.application;

import com.corebalt.isp.billing.infrastructure.Ledger;
import com.corebalt.isp.payments.api.MatchedPayments;
import com.corebalt.isp.shared.tenant.TenantIterator;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Money checks that alert rather than fix:
 *
 * <ul>
 *   <li>B4, nightly: a wallet whose cached balance differs from its ledger.
 *   <li>P4 guard, every 5 minutes: a MATCHED payment older than 5 minutes with no PAYMENT entry,
 *       which means a stuck or dead-lettered consumer. The reconciliation report (step 6) lists the
 *       same payments.
 * </ul>
 *
 * Each logs an error and sets a gauge Prometheus alerts on ({@code isp.billing.wallet.drift},
 * {@code isp.billing.payments.uncredited}). The per-tenant methods are what tests call.
 */
@Service
public class BillingChecks {

    private static final Logger log = LoggerFactory.getLogger(BillingChecks.class);

    /** How long a matched payment may wait for its ledger entry. */
    public static final Duration CREDIT_DEADLINE = Duration.ofMinutes(5);

    /** How far back the uncredited check looks. */
    static final Duration LOOKBACK = Duration.ofDays(7);

    private final Ledger ledger;
    private final MatchedPayments payments;
    private final TenantIterator tenants;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final AtomicInteger drifted = new AtomicInteger();
    private final AtomicInteger uncredited = new AtomicInteger();

    BillingChecks(
            final Ledger ledger,
            final MatchedPayments payments,
            final TenantIterator tenants,
            final TransactionTemplate tx,
            final Clock clock,
            final MeterRegistry meters) {
        this.ledger = ledger;
        this.payments = payments;
        this.tenants = tenants;
        this.tx = tx;
        this.clock = clock;
        meters.gauge("isp.billing.wallet.drift", drifted);
        meters.gauge("isp.billing.payments.uncredited", uncredited);
    }

    @Transactional(readOnly = true)
    public List<Ledger.Drift> walletDrift() {
        final List<Ledger.Drift> drift = ledger.drift();
        drift.forEach(d -> log.error("B4: wallet {} cached {} but ledger says {} (sum {})",
                d.walletId(), d.cachedMinor(), d.lastEntryMinor(), d.sumMinor()));
        return drift;
    }

    @Transactional(readOnly = true)
    public List<MatchedPayments.Matched> uncreditedPayments() {
        final Instant now = clock.instant();
        final List<MatchedPayments.Matched> missing =
                payments.matchedBetween(now.minus(LOOKBACK), now.minus(CREDIT_DEADLINE)).stream()
                        .filter(p -> !ledger.hasEntryFor("PAYMENT", p.paymentId()))
                        .toList();
        missing.forEach(p -> log.error("P4: payment {} matched at {} has no ledger entry", p.paymentId(), p.matchedAt()));
        return missing;
    }

    /**
     * Each tenant in its own transaction: row-level security needs the transaction that sets
     * {@code app.tenant_id}, which a call on {@code this} would not start.
     */
    @Scheduled(cron = "${isp.billing.checks.drift-cron:0 30 2 * * *}", zone = "UTC")
    public void nightlyDrift() {
        final AtomicInteger total = new AtomicInteger();
        tenants.forEachTenant("billing-wallet-drift", t -> total.addAndGet(tx.execute(s -> walletDrift()).size()));
        drifted.set(total.get());
    }

    @Scheduled(fixedDelayString = "${isp.billing.checks.uncredited-interval:5m}", initialDelayString = "${isp.billing.checks.initial-delay:5m}")
    public void uncreditedEveryFewMinutes() {
        final AtomicInteger total = new AtomicInteger();
        tenants.forEachTenant(
                "billing-uncredited-payments", t -> total.addAndGet(tx.execute(s -> uncreditedPayments()).size()));
        uncredited.set(total.get());
    }
}
