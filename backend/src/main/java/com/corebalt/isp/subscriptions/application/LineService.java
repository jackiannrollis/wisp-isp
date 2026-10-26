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
import com.corebalt.isp.shared.web.InvalidRequestException;
import com.corebalt.isp.shared.web.NotFoundException;
import com.corebalt.isp.shared.web.PageResponse;
import com.corebalt.isp.subscriptions.api.CreateLineRequest;
import com.corebalt.isp.subscriptions.api.LineCredentialStore;
import com.corebalt.isp.subscriptions.api.LineOpenedDto;
import com.corebalt.isp.subscriptions.api.PppoeCredentialDto;
import com.corebalt.isp.subscriptions.api.RouterDirectory;
import com.corebalt.isp.subscriptions.api.SubscriptionDirectory;
import com.corebalt.isp.subscriptions.api.SubscriptionDto;
import com.corebalt.isp.subscriptions.domain.DisplayStatus;
import com.corebalt.isp.subscriptions.domain.PppoeUsernames;
import com.corebalt.isp.subscriptions.domain.Subscription;
import com.corebalt.isp.subscriptions.domain.SubscriptionState;
import com.corebalt.isp.subscriptions.infrastructure.SubscriptionRepository;
import com.corebalt.isp.tenancy.api.TenantProfile;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
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
    private final TenantProfile tenantProfile;
    private final AuditTrail audit;
    private final SubscriptionViews views;
    private final Clock clock;

    LineService(
            final SubscriptionRepository lines,
            final CustomerFacade customers,
            final PackageCatalog packages,
            final RouterDirectory routers,
            final LineCredentialStore credentials,
            final TenantProfile tenantProfile,
            final AuditTrail audit,
            final SubscriptionViews views,
            final Clock clock) {
        this.lines = lines;
        this.customers = customers;
        this.packages = packages;
        this.routers = routers;
        this.credentials = credentials;
        this.tenantProfile = tenantProfile;
        this.audit = audit;
        this.views = views;
        this.clock = clock;
    }

    /**
     * Credentials the cutover import keeps from the ISP's old system (ADR 0014): the customer's router
     * already dials with them. Never logged, never returned.
     */
    public record KeptCredential(String username, String password) {}

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
        return open(customer, pkg, routerId, null);
    }

    /**
     * With {@code kept} (the cutover import, ADR 0014) the line keeps the old system's username and
     * password instead of generated ones, and the response carries no password: the router has it.
     */
    @Transactional
    LineOpenedDto open(final CustomerSummary customer, final PackageSummary pkg, final UUID routerId,
                       final KeptCredential kept) {
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
                        kept != null ? kept.username() : PppoeUsernames.forLine(customer.number(), lineNumber),
                        kept != null,
                        clock.instant());
        // Flush first: the credential row references the line (and a duplicate username, from a
        // concurrent request for the same customer, fails here as a 409 before any password exists).
        lines.saveAndFlush(line);
        final String password;
        if (kept != null) {
            credentials.keep(line.getId(), kept.password());
            password = null;
        } else {
            password = credentials.issue(line.getId());
        }
        audit.record(
                AuditEntry.of(
                        "subscriptions.line_opened",
                        "subscription",
                        line.getId(),
                        Map.of(
                                "pppoeUsername", line.getPppoeUsername(),
                                "usernameKept", kept != null,
                                "state", line.getState().name(),
                                "packageId", pkg.id().toString(),
                                "routerId", routerId.toString())));
        return new LineOpenedDto(views.toDto(line), password);
    }

    /** Which of these usernames a line of the tenant already has (the import's kept names). */
    @Transactional(readOnly = true)
    Set<String> existingUsernames(final Collection<String> usernames) {
        return usernames.isEmpty() ? Set.of() : new HashSet<>(lines.findPppoeUsernamesIn(usernames));
    }

    /**
     * A kept username typed wrong in the import (ADR 0014): corrected while the tenant's cutover
     * window is open, by the Owner, with an audit row. A generated username never changes (T2).
     */
    @Transactional
    public SubscriptionDto correctKeptUsername(final UUID subscriptionId, final String username) {
        final Instant now = clock.instant();
        if (tenantProfile.currentCutoverWindowUntil().filter(now::isBefore).isEmpty()) {
            throw new ConflictException("S11: the cutover window is closed; a Corebalt platform admin opens it.");
        }
        final String cleaned = username == null ? "" : username.strip();
        PppoeUsernames.keptProblem(cleaned).ifPresent(problem -> {
            throw InvalidRequestException.of("username", problem);
        });
        final Subscription line = lines.findForUpdate(subscriptionId).orElseThrow(() -> new NotFoundException("Subscription"));
        final String before = line.getPppoeUsername();
        line.correctKeptUsername(cleaned, now);
        lines.saveAndFlush(line);
        audit.record(new AuditEntry("subscriptions.pppoe_username_corrected", "subscription", line.getId(),
                Map.of("pppoeUsername", before), Map.of("pppoeUsername", cleaned), null));
        return views.toDto(line);
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
        return list(new LineFilter(customerId, null, null, null, null), page);
    }

    /**
     * Filters and pages in SQL. The display status (S1) is derived in the query by
     * subscription_line_display_status(), the SQL copy of the rules (with the line's cycle usage) that
     * SubscriptionAccessAgreementIT holds equal to AccessPolicy and DisplayStatus (S3); it is never
     * stored.
     */
    @Transactional(readOnly = true)
    public PageResponse<SubscriptionDto> list(final LineFilter filter, final Pageable page) {
        Specification<Subscription> where = Specification.unrestricted();
        if (filter.customerId() != null) {
            where = where.and((root, query, cb) -> cb.equal(root.get("customerId"), filter.customerId()));
        }
        if (filter.state() != null) {
            where = where.and((root, query, cb) -> cb.equal(root.get("state"), filter.state()));
        }
        if (filter.routerId() != null) {
            where = where.and((root, query, cb) -> cb.equal(root.get("routerId"), filter.routerId()));
        }
        if (filter.packageId() != null) {
            where = where.and((root, query, cb) -> cb.equal(root.get("packageId"), filter.packageId()));
        }
        if (filter.displayStatus() != null) {
            where = where.and((root, query, cb) -> cb.equal(
                    cb.function("subscription_line_display_status", String.class, root.get("id"), cb.currentTimestamp()),
                    filter.displayStatus().name()));
        }
        final Page<Subscription> found = lines.findAll(where, page);
        return PageResponse.of(found, views.mapper(found.getContent())::toDto);
    }

    /** Staff list filters; null means any. */
    public record LineFilter(UUID customerId, SubscriptionState state, UUID routerId, UUID packageId,
                             DisplayStatus displayStatus) {}

    @Override
    @Transactional(readOnly = true)
    public Optional<LineInfo> line(final UUID subscriptionId) {
        return lines.findById(subscriptionId).map(l -> new LineInfo(l.getId(), l.getCustomerId(), l.getPppoeUsername(),
                l.getPackageId(), l.packageForNextRenewal(), l.getExpiresAt(), l.getGraceDays(), l.getCycleStartedAt()));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> accessProfile(final UUID subscriptionId, final Instant at, final Instant expiresAt) {
        return lines.findById(subscriptionId).map(l -> views.profile(l, at, expiresAt).name());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> accessProfileBeforeLimit(final UUID subscriptionId, final Instant at, final boolean dataCap) {
        return lines.findById(subscriptionId).map(l -> views.profileBeforeLimit(l, at, dataCap).name());
    }

    private Subscription load(final UUID subscriptionId) {
        return lines.findById(subscriptionId).orElseThrow(() -> new NotFoundException("Subscription"));
    }
}
