package com.corebalt.isp.subscriptions.application;

import com.corebalt.isp.shared.audit.AuditEntry;
import com.corebalt.isp.shared.audit.AuditTrail;
import com.corebalt.isp.packages.api.PackageCatalog;
import com.corebalt.isp.packages.api.PackageCatalog.PackageTerms;
import com.corebalt.isp.shared.id.UuidV7;
import com.corebalt.isp.shared.money.Money;
import com.corebalt.isp.shared.outbox.OutboxPublisher;
import com.corebalt.isp.shared.web.NotFoundException;
import com.corebalt.isp.subscriptions.api.RenewalResultDto;
import com.corebalt.isp.subscriptions.api.SubscriptionDto;
import com.corebalt.isp.subscriptions.api.SubscriptionRenewed;
import com.corebalt.isp.subscriptions.api.SubscriptionStateChanged;
import com.corebalt.isp.subscriptions.api.SubscriptionStateChanged.Cause;
import com.corebalt.isp.subscriptions.api.WalletCharger;
import com.corebalt.isp.subscriptions.domain.ProRata;
import com.corebalt.isp.subscriptions.domain.GraceMode;
import com.corebalt.isp.subscriptions.domain.Subscription;
import com.corebalt.isp.subscriptions.domain.SubscriptionState;
import com.corebalt.isp.subscriptions.infrastructure.SubscriptionRepository;
import com.corebalt.isp.tenancy.api.TenantProfile;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Renews a line for one period of the package it is sold next (docs/04 Renewal rules, S5, S6, S10),
 * charging the wallet through {@link WalletCharger} in the same transaction. There is no HTTP
 * endpoint and never will be: renewals happen when money reaches the wallet (a payment or a staff
 * ADJUSTMENT with a reason) and, for lines whose wallet already holds the price, shortly before
 * expiry (the bounded E4 exception, {@code DueRenewals}).
 *
 * <p>Full price when the balance covers it (B5); otherwise, under the tenant's PRO_RATA_DAYS rule,
 * as many whole days as the balance buys; otherwise nothing and the money waits.
 */
@Service
public class RenewalService {

    private final SubscriptionRepository lines;
    private final PackageCatalog packages;
    private final TenantProfile tenantProfile;
    private final WalletCharger wallet;
    private final AuditTrail audit;
    private final OutboxPublisher outbox;
    private final SubscriptionViews views;
    private final Clock clock;

    RenewalService(
            final SubscriptionRepository lines,
            final PackageCatalog packages,
            final TenantProfile tenantProfile,
            final WalletCharger wallet,
            final AuditTrail audit,
            final OutboxPublisher outbox,
            final SubscriptionViews views,
            final Clock clock) {
        this.lines = lines;
        this.packages = packages;
        this.tenantProfile = tenantProfile;
        this.wallet = wallet;
        this.audit = audit;
        this.outbox = outbox;
        this.views = views;
        this.clock = clock;
    }

    /**
     * Charges and extends the line if the wallet allows. The line is row-locked first, so two
     * renewals of one line run one after the other; the charge locks the wallet, so two lines
     * cannot both spend one balance.
     */
    @Transactional
    public RenewalResultDto renew(final UUID subscriptionId) {
        final Subscription line =
                lines.findForUpdate(subscriptionId).orElseThrow(() -> new NotFoundException("Subscription"));
        // Fail before charging if the line cannot be renewed (paused, suspended, terminated).
        line.requireRenewable();
        final SubscriptionDto beforeLine = views.toDto(line);
        final String before = beforeLine.accessProfile();
        final PackageTerms terms =
                packages.terms(line.packageForNextRenewal()).orElseThrow(() -> new NotFoundException("Package"));
        final Money price = terms.price();
        final Money balance = wallet.balance(line.getCustomerId(), price.currency());

        final Duration period;
        final Money charge;
        final Integer proRataDays;
        if (balance.isGreaterThanOrEqualTo(price)) {
            period = Duration.ofMinutes(terms.durationMinutes());
            charge = price;
            proRataDays = null;
        } else {
            final Optional<ProRata.Grant> grant = proRata(balance, price, terms.durationMinutes());
            if (grant.isEmpty()) {
                return insufficient(line);
            }
            period = Duration.ofDays(grant.get().days());
            charge = grant.get().charge();
            proRataDays = grant.get().days();
        }

        final UUID renewalId = UuidV7.next(clock);
        final WalletCharger.Outcome outcome = wallet.charge(
                line.getCustomerId(), charge, WalletCharger.Kind.RENEWAL, renewalId,
                "Renewal " + line.getPppoeUsername() + ", " + terms.name());
        if (outcome != WalletCharger.Outcome.CHARGED) {
            // Another charge got there first, under the wallet lock.
            return insufficient(line);
        }
        // S6: grace days for the new cycle come from the package, else the tenant default.
        final int graceDays =
                terms.graceDays() != null ? terms.graceDays() : tenantProfile.currentPolicy().defaultGraceDays();
        final Instant now = clock.instant();
        // S6: the grace mode is kept with the cycle too, so a later policy change leaves it alone.
        final GraceMode graceMode = GraceMode.valueOf(tenantProfile.currentPolicy().graceMode());
        final Subscription.Renewal renewal = line.renew(period, graceDays, graceMode, terms.id(), now);

        final Map<String, Object> after = new LinkedHashMap<>();
        after.put("state", line.getState().name());
        after.put("expiresAt", line.getExpiresAt().toString());
        after.put("chargedMinor", charge.minorUnits());
        after.put("renewalId", renewalId.toString());
        audit.record(new AuditEntry("subscriptions.renewed", "subscription", line.getId(),
                renewal.previousExpiresAt() == null
                        ? Map.of("state", renewal.from().name())
                        : Map.of("state", renewal.from().name(), "expiresAt", renewal.previousExpiresAt().toString()),
                after, null));
        outbox.publish(new SubscriptionRenewed(
                UuidV7.next(clock), line.getTenantId(), now, line.getId(), renewalId, line.getCustomerId(), terms.id(),
                renewal.previousExpiresAt(), renewal.expiresAt(), charge.minorUnits(),
                charge.currency().getCurrencyCode(), proRataDays, before));
        if (renewal.from() != SubscriptionState.ACTIVE) {
            outbox.publish(new SubscriptionStateChanged(
                    UuidV7.next(clock), line.getTenantId(), now, line.getId(), line.getCustomerId(),
                    renewal.from().name(), SubscriptionState.ACTIVE.name(), Cause.RENEWAL));
        }
        return new RenewalResultDto(RenewalResultDto.Outcome.RENEWED, views.toDto(line), before, beforeLine.accessReason());
    }

    private Optional<ProRata.Grant> proRata(final Money balance, final Money price, final int durationMinutes) {
        if (!"PRO_RATA_DAYS".equals(tenantProfile.currentPolicy().partialPaymentRule()) || durationMinutes % 1440 != 0) {
            return Optional.empty();
        }
        return ProRata.grant(balance, price, durationMinutes / 1440);
    }

    private RenewalResultDto insufficient(final Subscription line) {
        final var dto = views.toDto(line);
        return new RenewalResultDto(RenewalResultDto.Outcome.INSUFFICIENT_BALANCE, dto, dto.accessProfile(), dto.accessReason());
    }
}
