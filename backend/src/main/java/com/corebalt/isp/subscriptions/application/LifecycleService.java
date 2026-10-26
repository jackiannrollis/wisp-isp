package com.corebalt.isp.subscriptions.application;

import com.corebalt.isp.packages.api.PackageCatalog.PackageTerms;
import com.corebalt.isp.shared.audit.AuditEntry;
import com.corebalt.isp.shared.audit.AuditTrail;
import com.corebalt.isp.shared.id.UuidV7;
import com.corebalt.isp.shared.outbox.OutboxPublisher;
import com.corebalt.isp.shared.web.NotFoundException;
import com.corebalt.isp.subscriptions.api.LineCredentialStore;
import com.corebalt.isp.subscriptions.api.SubscriptionDto;
import com.corebalt.isp.subscriptions.api.SubscriptionStateChanged;
import com.corebalt.isp.subscriptions.api.SubscriptionStateChanged.Cause;
import com.corebalt.isp.subscriptions.domain.GraceMode;
import com.corebalt.isp.subscriptions.domain.Subscription;
import com.corebalt.isp.subscriptions.domain.Subscription.Transition;
import com.corebalt.isp.subscriptions.infrastructure.SubscriptionRepository;
import com.corebalt.isp.tenancy.api.TenantProfile;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stored-state transitions (docs/04): pause, resume, suspend, lift, terminate. Each one locks the
 * line, writes an audit row with the reason (A1) and publishes {@link SubscriptionStateChanged}
 * through the outbox (S9, E1), all in one transaction.
 */
@Service
public class LifecycleService {

    private final SubscriptionRepository lines;
    private final LineCredentialStore credentials;
    private final TenantProfile tenantProfile;
    private final AuditTrail audit;
    private final OutboxPublisher outbox;
    private final SubscriptionViews views;
    private final Clock clock;

    LifecycleService(
            final SubscriptionRepository lines,
            final LineCredentialStore credentials,
            final TenantProfile tenantProfile,
            final AuditTrail audit,
            final OutboxPublisher outbox,
            final SubscriptionViews views,
            final Clock clock) {
        this.lines = lines;
        this.credentials = credentials;
        this.tenantProfile = tenantProfile;
        this.audit = audit;
        this.outbox = outbox;
        this.views = views;
        this.clock = clock;
    }

    @Transactional
    public SubscriptionDto pause(final UUID subscriptionId, final Instant until, final String reason) {
        final int maxPauseDays = tenantProfile.currentPolicy().maxPauseDays();
        return apply(subscriptionId, "subscriptions.paused", reason, (line, now) -> line.pause(until, now, maxPauseDays));
    }

    /** Before pause_until a manual resume (Disconnect needed); after it, recording what RADIUS already did. */
    @Transactional
    public SubscriptionDto resume(final UUID subscriptionId, final String reason) {
        return apply(subscriptionId, "subscriptions.resumed", reason, (line, now) -> line.resume(now));
    }

    @Transactional
    public SubscriptionDto suspend(final UUID subscriptionId, final String reason) {
        return apply(subscriptionId, "subscriptions.suspended", reason, (line, now) -> line.suspend(now));
    }

    @Transactional
    public SubscriptionDto liftSuspension(final UUID subscriptionId, final String reason) {
        return apply(subscriptionId, "subscriptions.suspension_lifted", reason, (line, now) -> line.liftSuspension(now));
    }

    /** Also revokes the PPPoE password, so the line can never log in again, and frees the static IP. */
    @Transactional
    public SubscriptionDto terminate(final UUID subscriptionId, final String reason) {
        final SubscriptionDto terminated =
                apply(subscriptionId, "subscriptions.terminated", reason, (line, now) -> line.terminate(now));
        credentials.revoke(subscriptionId);
        return terminated;
    }

    /**
     * Cancel before activation (docs/04): a PENDING line closes, its PPPoE credential is revoked so the
     * RADIUS view has nothing for it (ADR 0009), and the wallet is untouched; a refund takes the normal
     * road. Audited with its reason (A1) and published like every transition (S9).
     */
    @Transactional
    public SubscriptionDto cancelBeforeActivation(final UUID subscriptionId, final String reason) {
        final SubscriptionDto cancelled = apply(subscriptionId, "subscriptions.cancelled", reason,
                (line, now) -> line.cancelBeforeActivation(now));
        credentials.revoke(subscriptionId);
        return cancelled;
    }

    /** For the job: record a resume the RADIUS view applied at pause_until (E4). */
    @Transactional
    public void recordAutoResume(final UUID subscriptionId) {
        apply(subscriptionId, "subscriptions.resumed", "pause_until reached", (line, now) -> line.resume(now));
    }

    /**
     * S11 (ADR 0014): the cutover import activates a line it just opened with the expiry its customer
     * paid for in the ISP's old system. No ledger entry (the old system took that money), an audit
     * row naming the import batch and row, and {@link SubscriptionStateChanged} with cause
     * {@link Cause#IMPORTED}, which no consumer acts on: the line has no session yet. Grace is copied
     * as a renewal copies it (S6). Runs inside the import's transaction.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public SubscriptionDto activateByImport(final UUID subscriptionId, final Instant paidUntil, final PackageTerms pkg,
                                            final UUID batchId, final int row) {
        final int graceDays = pkg.graceDays() != null ? pkg.graceDays() : tenantProfile.currentPolicy().defaultGraceDays();
        final GraceMode graceMode = GraceMode.valueOf(tenantProfile.currentPolicy().graceMode());
        final Subscription line =
                lines.findForUpdate(subscriptionId).orElseThrow(() -> new NotFoundException("Subscription"));
        final Instant now = clock.instant();
        final Map<String, Object> before = snapshot(line);
        final Transition transition =
                line.activateByImport(paidUntil, Duration.ofMinutes(pkg.durationMinutes()), graceDays, graceMode, now);
        final Map<String, Object> after = snapshot(line);
        after.put("cycleStartedAt", line.getCycleStartedAt().toString());
        after.put("importBatchId", batchId.toString());
        after.put("row", row);
        audit.record(new AuditEntry("subscriptions.activated_by_import", "subscription", line.getId(), before, after, null));
        outbox.publish(new SubscriptionStateChanged(UuidV7.next(clock), line.getTenantId(), now, line.getId(),
                line.getCustomerId(), transition.from().name(), transition.to().name(), Cause.IMPORTED));
        return views.toDto(line);
    }

    private SubscriptionDto apply(
            final UUID subscriptionId,
            final String action,
            final String reason,
            final TransitionStep step) {
        final Subscription line =
                lines.findForUpdate(subscriptionId).orElseThrow(() -> new NotFoundException("Subscription"));
        final Instant now = clock.instant();
        final Map<String, Object> before = snapshot(line);
        final Transition transition = step.apply(line, now);
        audit.record(new AuditEntry(action, "subscription", line.getId(), before, snapshot(line), reason));
        outbox.publish(
                new SubscriptionStateChanged(
                        UuidV7.next(clock),
                        line.getTenantId(),
                        now,
                        line.getId(),
                        line.getCustomerId(),
                        transition.from().name(),
                        transition.to().name(),
                        transition.recorded() ? Cause.AUTO_RESUME_RECORDED : Cause.STAFF_ACTION));
        return views.toDto(line);
    }

    static Map<String, Object> snapshot(final Subscription line) {
        final Map<String, Object> state = new LinkedHashMap<>();
        state.put("state", line.getState().name());
        state.put("expiresAt", line.getExpiresAt() == null ? null : line.getExpiresAt().toString());
        state.put("pauseUntil", line.getPauseUntil() == null ? null : line.getPauseUntil().toString());
        return state;
    }

    @FunctionalInterface
    private interface TransitionStep {
        Transition apply(Subscription line, Instant now);
    }
}
