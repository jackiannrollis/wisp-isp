package com.corebalt.isp.subscriptions.application;

import com.corebalt.isp.customers.api.CustomerFacade;
import com.corebalt.isp.customers.api.CustomerSummary;
import com.corebalt.isp.shared.audit.AuditEntry;
import com.corebalt.isp.shared.audit.AuditTrail;
import com.corebalt.isp.packages.api.PackageCatalog;
import com.corebalt.isp.packages.api.PackageCatalog.PackageSummary;
import com.corebalt.isp.shared.id.UuidV7;
import com.corebalt.isp.shared.tenant.TenantContext;
import com.corebalt.isp.shared.web.ConflictException;
import com.corebalt.isp.shared.web.NotFoundException;
import com.corebalt.isp.shared.web.PageResponse;
import com.corebalt.isp.subscriptions.api.CreateLineRequest;
import com.corebalt.isp.subscriptions.api.LineCredentialStore;
import com.corebalt.isp.subscriptions.api.LineOpenedDto;
import com.corebalt.isp.subscriptions.api.PppoeCredentialDto;
import com.corebalt.isp.subscriptions.api.RouterDirectory;
import com.corebalt.isp.subscriptions.api.SubscriptionDirectory;
import com.corebalt.isp.subscriptions.api.SubscriptionDto;
import com.corebalt.isp.subscriptions.domain.PppoeUsernames;
import com.corebalt.isp.subscriptions.domain.Subscription;
import com.corebalt.isp.subscriptions.infrastructure.SubscriptionRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Opens PPPoE lines and replaces their passwords (step 3). The password is generated and stored by
 * the network module behind {@link LineCredentialStore}, in this transaction, and returned once.
 * No Idempotency-Key on these writes: a replay would have to return the password again, so a
 * retried regeneration simply issues another one (decisions.md 2026-10-19).
 */
@Service
public class LineService implements SubscriptionDirectory {

    private final SubscriptionRepository lines;
    private final CustomerFacade customers;
    private final PackageCatalog packages;
    private final RouterDirectory routers;
    private final LineCredentialStore credentials;
    private final AuditTrail audit;
    private final SubscriptionViews views;
    private final Clock clock;

    LineService(
            final SubscriptionRepository lines,
            final CustomerFacade customers,
            final PackageCatalog packages,
            final RouterDirectory routers,
            final LineCredentialStore credentials,
            final AuditTrail audit,
            final SubscriptionViews views,
            final Clock clock) {
        this.lines = lines;
        this.customers = customers;
        this.packages = packages;
        this.routers = routers;
        this.credentials = credentials;
        this.audit = audit;
        this.views = views;
        this.clock = clock;
    }

    @Transactional
    public LineOpenedDto open(final CreateLineRequest request) {
        final CustomerSummary customer =
                customers.find(request.customerId()).orElseThrow(() -> new NotFoundException("Customer"));
        final PackageSummary pkg =
                packages.find(request.packageId()).orElseThrow(() -> new NotFoundException("Package"));
        if (!routers.exists(request.routerId())) {
            throw new NotFoundException("Router");
        }
        return open(customer, pkg, request.routerId());
    }

    /** For callers that already resolved the customer, package and router (the CSV import). */
    @Transactional
    LineOpenedDto open(final CustomerSummary customer, final PackageSummary pkg, final UUID routerId) {
        if (!customer.active()) {
            throw new ConflictException("Customer " + customer.number() + " is archived.");
        }
        if (!pkg.sellableAsPppoe()) {
            throw new ConflictException("Package '" + pkg.name() + "' is not an active PPPoE package.");
        }
        final int lineNumber = Math.toIntExact(lines.countByCustomerId(customer.id()) + 1);
        final Subscription line =
                Subscription.open(
                        UuidV7.next(clock),
                        TenantContext.requireTenantId(),
                        customer.id(),
                        pkg.id(),
                        routerId,
                        PppoeUsernames.forLine(customer.number(), lineNumber),
                        clock.instant());
        // Flush first: the credential row references the line (and a duplicate username, from a
        // concurrent request for the same customer, fails here as a 409 before any password exists).
        lines.saveAndFlush(line);
        final String password = credentials.issue(line.getId());
        audit.record(
                AuditEntry.of(
                        "subscriptions.line_opened",
                        "subscription",
                        line.getId(),
                        Map.of(
                                "pppoeUsername", line.getPppoeUsername(),
                                "state", line.getState().name(),
                                "packageId", pkg.id().toString(),
                                "routerId", routerId.toString())));
        return new LineOpenedDto(views.toDto(line), password);
    }

    @Transactional
    public PppoeCredentialDto regeneratePassword(final UUID subscriptionId) {
        final Subscription line = load(subscriptionId);
        return new PppoeCredentialDto(line.getId(), line.getPppoeUsername(), credentials.regenerate(line.getId()));
    }

    @Transactional(readOnly = true)
    public SubscriptionDto get(final UUID subscriptionId) {
        return views.toDto(load(subscriptionId));
    }

    @Transactional(readOnly = true)
    public PageResponse<SubscriptionDto> list(final UUID customerId, final Pageable page) {
        final SubscriptionViews.Mapper mapper = views.mapper();
        return PageResponse.of(
                customerId == null ? lines.findAll(page) : lines.findByCustomerId(customerId, page),
                mapper::toDto);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<LineInfo> line(final UUID subscriptionId) {
        return lines.findById(subscriptionId).map(l -> new LineInfo(l.getId(), l.getCustomerId(), l.getPppoeUsername(),
                l.getPackageId(), l.packageForNextRenewal(), l.getExpiresAt(), l.getGraceDays()));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> accessProfile(final UUID subscriptionId, final Instant at, final Instant expiresAt) {
        return lines.findById(subscriptionId).map(l -> views.profile(l, at, expiresAt).name());
    }

    private Subscription load(final UUID subscriptionId) {
        return lines.findById(subscriptionId).orElseThrow(() -> new NotFoundException("Subscription"));
    }
}
