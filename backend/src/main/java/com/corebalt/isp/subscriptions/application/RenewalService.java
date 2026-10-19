package com.corebalt.isp.subscriptions.application;

import com.corebalt.isp.identity.api.AuditEntry;
import com.corebalt.isp.identity.api.AuditTrail;
import com.corebalt.isp.packages.api.PackageCatalog;
import com.corebalt.isp.packages.api.PackageCatalog.PackageTerms;
import com.corebalt.isp.shared.id.UuidV7;
import com.corebalt.isp.shared.outbox.OutboxPublisher;
import com.corebalt.isp.shared.web.NotFoundException;
import com.corebalt.isp.subscriptions.api.RenewalResultDto;
import com.corebalt.isp.subscriptions.api.SubscriptionRenewed;
import com.corebalt.isp.subscriptions.api.SubscriptionStateChanged;
import com.corebalt.isp.subscriptions.api.SubscriptionStateChanged.Cause;
import com.corebalt.isp.subscriptions.api.WalletCharger;
import com.corebalt.isp.subscriptions.domain.Subscription;
import com.corebalt.isp.subscriptions.domain.SubscriptionState;
import com.corebalt.isp.subscriptions.infrastructure.SubscriptionRepository;
import com.corebalt.isp.tenancy.api.TenantProfile;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Renews a line for one period of its package (docs/04 Renewal rules, S5, S6), charging the
 * customer's wallet through {@link WalletCharger} in the same transaction. There is no HTTP
 * endpoint and never will be: renewals happen when money arrives (step 5's WalletCredited consumer)
 * or through a wallet adjustment with a reason, both of which charge through the same port.
 *
 * <p>The billing module implements {@link WalletCharger} in step 5; until then no implementation
 * exists outside tests and calling this fails.
 */
@Service
public class RenewalService {

    private final SubscriptionRepository lines;
    private final PackageCatalog packages;
    private final TenantProfile tenantProfile;
    private final ObjectProvider<WalletCharger> walletCharger;
    private final AuditTrail audit;
    private final OutboxPublisher outbox;
    private final SubscriptionViews views;
    private final Clock clock;

    RenewalService(
            final SubscriptionRepository lines,
            final PackageCatalog packages,
            final TenantProfile tenantProfile,
            final ObjectProvider<WalletCharger> walletCharger,
            final AuditTrail audit,
            final OutboxPublisher outbox,
            final SubscriptionViews views,
            final Clock clock) {
        this.lines = lines;
        this.packages = packages;
        this.tenantProfile = tenantProfile;
        this.walletCharger = walletCharger;
        this.audit = audit;
        this.outbox = outbox;
        this.views = views;
        this.clock = clock;
    }

    /**
     * Charges the package price and, if the wallet covers it (B5), extends the line. The line is
     * row-locked first, so two renewals of one line run one after the other; the charger locks the
     * wallet, so two lines cannot both spend one payment.
     */
    @Transactional
    public RenewalResultDto renew(final UUID subscriptionId) {
        final WalletCharger charger = walletCharger.getIfAvailable();
        if (charger == null) {
            throw new IllegalStateException("No WalletCharger: the billing module provides it in step 5");
        }
        final Subscription line =
                lines.findForUpdate(subscriptionId).orElseThrow(() -> new NotFoundException("Subscription"));
        final PackageTerms terms =
                packages.terms(line.getPackageId()).orElseThrow(() -> new NotFoundException("Package"));
        final Instant now = clock.instant();
        // Fail before charging if the line cannot be renewed (paused, suspended, terminated).
        line.requireRenewable();
        final String reference =
                "renewal:" + line.getId() + ":" + (line.getExpiresAt() == null ? "activation" : line.getExpiresAt());
        if (charger.charge(line.getCustomerId(), terms.price(), reference) == WalletCharger.Outcome.INSUFFICIENT_BALANCE) {
            return new RenewalResultDto(RenewalResultDto.Outcome.INSUFFICIENT_BALANCE, views.toDto(line));
        }
        // S6: grace days for the new cycle come from the package, else the tenant default.
        final int graceDays =
                terms.graceDays() != null ? terms.graceDays() : tenantProfile.currentPolicy().defaultGraceDays();
        final Subscription.Renewal renewal =
                line.renew(Duration.ofMinutes(terms.durationMinutes()), graceDays, now);

        audit.record(new AuditEntry(
                "subscriptions.renewed",
                "subscription",
                line.getId(),
                LifecycleSnapshot.of(renewal.from(), renewal.previousExpiresAt()),
                LifecycleSnapshot.of(line.getState(), line.getExpiresAt()),
                null));
        outbox.publish(new SubscriptionRenewed(
                UuidV7.next(clock),
                line.getTenantId(),
                now,
                line.getId(),
                line.getCustomerId(),
                line.getPackageId(),
                renewal.previousExpiresAt(),
                renewal.expiresAt(),
                terms.price().minorUnits(),
                terms.price().currency().getCurrencyCode()));
        if (renewal.from() != SubscriptionState.ACTIVE) {
            outbox.publish(new SubscriptionStateChanged(
                    UuidV7.next(clock),
                    line.getTenantId(),
                    now,
                    line.getId(),
                    line.getCustomerId(),
                    renewal.from().name(),
                    SubscriptionState.ACTIVE.name(),
                    Cause.RENEWAL));
        }
        return new RenewalResultDto(RenewalResultDto.Outcome.RENEWED, views.toDto(line));
    }

    private static final class LifecycleSnapshot {

        static Map<String, Object> of(final SubscriptionState state, final Instant expiresAt) {
            return expiresAt == null
                    ? Map.of("state", state.name())
                    : Map.of("state", state.name(), "expiresAt", expiresAt.toString());
        }
    }
}
