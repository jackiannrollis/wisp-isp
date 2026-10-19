package com.corebalt.isp.subscriptions.application;

import com.corebalt.isp.packages.api.PackageCatalog;
import com.corebalt.isp.packages.api.PackageCatalog.PackageTerms;
import com.corebalt.isp.subscriptions.api.RenewalResultDto;
import com.corebalt.isp.subscriptions.domain.Subscription;
import com.corebalt.isp.subscriptions.infrastructure.SubscriptionRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import com.corebalt.isp.shared.id.UuidV7;
import com.corebalt.isp.shared.money.Money;
import com.corebalt.isp.shared.outbox.OutboxPublisher;
import com.corebalt.isp.shared.tenant.TenantContext;
import com.corebalt.isp.subscriptions.api.CreditApplied;
import com.corebalt.isp.subscriptions.api.WalletCharger;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * When renewals happen (docs/05 Prepaid):
 *
 * <ul>
 *   <li><b>On credit</b> (WalletCredited: a payment or a positive adjustment): every PENDING or ACTIVE
 *       line of the customer, earliest expiry first (PENDING first), is renewed at most once while
 *       the wallet covers it. An early payment extends from the current expiry (S5).
 *   <li><b>When due</b> (the hourly job): ACTIVE lines on prepaid packages whose expiry is less than
 *       {@code min(24 h, package duration / 4)} away (already-expired ones included) are renewed from
 *       credit already in the wallet. This is the one bounded exception to E4: a job outage shorter
 *       than the window never restricts a customer whose wallet holds the price, and renewing early
 *       loses nothing because it extends from expiry.
 * </ul>
 */
@Service
public class RenewalTriggers {

    private static final Logger log = LoggerFactory.getLogger(RenewalTriggers.class);

    /** The longest lead time of a due renewal. */
    public static final Duration MAX_DUE_WINDOW = Duration.ofHours(24);

    private final SubscriptionRepository lines;
    private final RenewalService renewals;
    private final PackageCatalog packages;
    private final TransactionTemplate tx;
    private final WalletCharger wallet;
    private final OutboxPublisher outbox;
    private final Clock clock;

    RenewalTriggers(
            final SubscriptionRepository lines,
            final RenewalService renewals,
            final PackageCatalog packages,
            final TransactionTemplate tx,
            final WalletCharger wallet,
            final OutboxPublisher outbox,
            final Clock clock) {
        this.lines = lines;
        this.renewals = renewals;
        this.packages = packages;
        this.tx = tx;
        this.wallet = wallet;
        this.outbox = outbox;
        this.clock = clock;
    }

    /** A credit as WalletCredited reports it. */
    public record Credit(UUID customerId, String entryType, UUID referenceId, long amountMinor, String currency) {}

    /**
     * In the WalletCredited consumer's transaction: renews what the balance covers, then publishes
     * {@link CreditApplied} with the outcome (the payment SMS is built from it). Returns how many
     * lines renewed.
     */
    @Transactional
    public int renewOnCredit(final Credit credit) {
        final List<CreditApplied.RenewedLine> renewed = new ArrayList<>();
        UUID firstUncovered = null;
        for (final Subscription line : lines.findRenewableByCustomer(credit.customerId())) {
            final RenewalResultDto result = renewals.renew(line.getId());
            if (result.outcome() == RenewalResultDto.Outcome.RENEWED) {
                renewed.add(new CreditApplied.RenewedLine(line.getId(), result.subscription().packageId(),
                        result.subscription().expiresAt(), result.previousAccessProfile()));
            } else if (firstUncovered == null) {
                firstUncovered = line.getId();
            }
        }
        final Currency currency = Currency.getInstance(credit.currency());
        final Money balance = wallet.balance(credit.customerId(), currency);
        CreditApplied.Due due = null;
        if (firstUncovered != null) {
            final Subscription line = lines.findById(firstUncovered).orElseThrow();
            final Optional<PackageTerms> terms = packages.terms(line.packageForNextRenewal());
            if (terms.isPresent()) {
                final Money missing = terms.get().price().minus(balance);
                if (missing.isPositive()) {
                    due = new CreditApplied.Due(line.getId(), terms.get().id(), missing.minorUnits());
                }
            }
        }
        final Instant now = clock.instant();
        outbox.publish(new CreditApplied(UuidV7.next(clock), TenantContext.requireTenantId(), now,
                credit.customerId(), credit.entryType(), credit.referenceId(), credit.amountMinor(), credit.currency(),
                List.copyOf(renewed), balance.minorUnits(), due));
        return renewed.size();
    }

    /** The current tenant's due lines, each renewal in its own transaction; returns how many renewed. */
    public int renewDue() {
        final Instant now = clock.instant();
        final List<Subscription> candidates =
                tx.execute(s -> lines.findActiveExpiringBefore(now.plus(MAX_DUE_WINDOW)));
        int renewed = 0;
        for (final Subscription line : candidates == null ? List.<Subscription>of() : candidates) {
            final boolean due = Boolean.TRUE.equals(tx.execute(s -> isDue(line, now)));
            if (!due) {
                continue;
            }
            try {
                if (renewals.renew(line.getId()).outcome() == RenewalResultDto.Outcome.RENEWED) {
                    renewed++;
                }
            } catch (RuntimeException e) {
                log.warn("Due renewal of {} failed; the next run retries", line.getId(), e);
            }
        }
        return renewed;
    }

    /** {@code expires_at < now + min(24 h, duration / 4)} on a prepaid package. */
    private boolean isDue(final Subscription line, final Instant now) {
        final Optional<PackageTerms> terms = packages.terms(line.packageForNextRenewal());
        if (terms.isEmpty() || !terms.get().prepaid()) {
            return false;
        }
        return line.getExpiresAt().isBefore(now.plus(window(terms.get().durationMinutes())));
    }

    static Duration window(final int durationMinutes) {
        final Duration quarter = Duration.ofMinutes(durationMinutes).dividedBy(4);
        return quarter.compareTo(MAX_DUE_WINDOW) < 0 ? quarter : MAX_DUE_WINDOW;
    }
}
