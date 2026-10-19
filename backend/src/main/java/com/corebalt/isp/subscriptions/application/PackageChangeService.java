package com.corebalt.isp.subscriptions.application;

import com.corebalt.isp.identity.api.AuditEntry;
import com.corebalt.isp.identity.api.AuditTrail;
import com.corebalt.isp.packages.api.PackageCatalog;
import com.corebalt.isp.packages.api.PackageCatalog.PackageTerms;
import com.corebalt.isp.shared.id.UuidV7;
import com.corebalt.isp.shared.money.Money;
import com.corebalt.isp.shared.outbox.OutboxPublisher;
import com.corebalt.isp.shared.web.ConflictException;
import com.corebalt.isp.shared.web.NotFoundException;
import com.corebalt.isp.subscriptions.api.SubscriptionDto;
import com.corebalt.isp.subscriptions.api.SubscriptionPackageChanged;
import com.corebalt.isp.subscriptions.api.WalletCharger;
import com.corebalt.isp.subscriptions.domain.Subscription;
import com.corebalt.isp.subscriptions.infrastructure.SubscriptionRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Package changes (S10, docs/05 Package changes):
 *
 * <ul>
 *   <li>Upgrade now: {@code PRORATION_CHARGE = (new − old) × remaining ÷ duration}, rounded up to
 *       the shilling (B6), the remaining part of the paid cycle measured to the second; the expiry
 *       does not move; the network module changes the speed by CoA. Needs the balance to cover the
 *       charge (409 otherwise; the portal will offer STK Push for the difference).
 *   <li>Downgrade, or upgrade at the next cycle: the package waits on the line and the next renewal
 *       sells it at full price. No refunds mid-cycle.
 * </ul>
 */
@Service
public class PackageChangeService {

    public enum Timing {
        NOW,
        NEXT_RENEWAL
    }

    private final SubscriptionRepository lines;
    private final PackageCatalog packages;
    private final WalletCharger wallet;
    private final AuditTrail audit;
    private final OutboxPublisher outbox;
    private final SubscriptionViews views;
    private final Clock clock;

    PackageChangeService(
            final SubscriptionRepository lines,
            final PackageCatalog packages,
            final WalletCharger wallet,
            final AuditTrail audit,
            final OutboxPublisher outbox,
            final SubscriptionViews views,
            final Clock clock) {
        this.lines = lines;
        this.packages = packages;
        this.wallet = wallet;
        this.audit = audit;
        this.outbox = outbox;
        this.views = views;
        this.clock = clock;
    }

    @Transactional
    public SubscriptionDto change(final UUID subscriptionId, final UUID newPackageId, final Timing timing) {
        final Subscription line =
                lines.findForUpdate(subscriptionId).orElseThrow(() -> new NotFoundException("Subscription"));
        final PackageTerms current =
                packages.terms(line.getPackageId()).orElseThrow(() -> new NotFoundException("Package"));
        final PackageTerms target = packages.terms(newPackageId).orElseThrow(() -> new NotFoundException("Package"));
        if (!target.sellableAsPppoe()) {
            throw new ConflictException("Package '" + target.name() + "' is not an active PPPoE package.");
        }
        if (target.id().equals(line.getPackageId()) && line.getPendingPackageId() == null) {
            throw new ConflictException("The line already has package '" + target.name() + "'.");
        }
        final Instant now = clock.instant();
        Money proration = Money.zero(target.price().currency());
        if (timing == Timing.NOW) {
            if (target.price().compareTo(current.price()) < 0) {
                throw new ConflictException("S10: a downgrade applies at the next renewal, never immediately.");
            }
            if (line.getExpiresAt() == null || !now.isBefore(line.getExpiresAt())) {
                throw new ConflictException("S10: an immediate change needs paid time remaining; change it at the next renewal.");
            }
            final long remaining = Duration.between(now, line.getExpiresAt()).toSeconds();
            final long duration = Duration.ofMinutes(current.durationMinutes()).toSeconds();
            proration = Money.proration(current.price(), target.price(), remaining, duration);
            if (proration.isPositive()) {
                final WalletCharger.Outcome outcome = wallet.charge(
                        line.getCustomerId(), proration, WalletCharger.Kind.PRORATION, UuidV7.next(clock),
                        "Upgrade " + line.getPppoeUsername() + " to " + target.name());
                if (outcome != WalletCharger.Outcome.CHARGED) {
                    throw new ConflictException("B6: the upgrade costs " + proration.toMajor() + " "
                            + proration.currency().getCurrencyCode() + " now and the wallet does not cover it.");
                }
            }
            line.changePackageNow(target.id(), now);
        } else {
            line.changePackageAtRenewal(target.id(), now);
        }

        final Map<String, Object> after = new LinkedHashMap<>();
        after.put("packageId", line.getPackageId().toString());
        after.put("pendingPackageId", line.getPendingPackageId() == null ? null : line.getPendingPackageId().toString());
        after.put("prorationMinor", proration.minorUnits());
        audit.record(new AuditEntry("subscriptions.package_changed", "subscription", line.getId(),
                Map.of("packageId", current.id().toString()), after, timing.name()));
        outbox.publish(new SubscriptionPackageChanged(
                UuidV7.next(clock), line.getTenantId(), now, line.getId(), line.getCustomerId(), current.id(),
                target.id(), timing.name(), proration.minorUnits(), proration.currency().getCurrencyCode()));
        return views.toDto(line);
    }
}
