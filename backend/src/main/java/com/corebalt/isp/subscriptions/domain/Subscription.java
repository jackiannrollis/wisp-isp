package com.corebalt.isp.subscriptions.domain;

import com.corebalt.isp.tenancy.api.ProbeTenant;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A service line (docs/03 subscriptions) and its lifecycle (docs/04). Only the five stored states
 * exist here (S1); the access profile and display status are derived by {@link AccessPolicy} and
 * {@link DisplayStatus}. Other modules' rows are referenced by ID only.
 *
 * <pre>
 * PENDING  -> ACTIVE      renewal (first one paid)
 * ACTIVE   -> ACTIVE      renewal
 * ACTIVE   -> PAUSED      pause
 * PAUSED   -> ACTIVE      resume (manual, or recorded after pause_until)
 * ACTIVE, PAUSED -> SUSPENDED
 * SUSPENDED -> ACTIVE     lift
 * ACTIVE, SUSPENDED -> TERMINATED
 * PENDING  -> TERMINATED  cancel before activation (never paid)
 * </pre>
 */
@Entity
@Table(name = "subscriptions_subscription")
public class Subscription {

    @Id private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private UUID customerId;

    @Column(name = "package_id", nullable = false)
    private UUID packageId;

    @Column(name = "router_id", nullable = false)
    private UUID routerId;

    @Column(name = "pending_package_id")
    private UUID pendingPackageId;

    /** Generated from the customer number (T2), or kept from the ISP's old system by the cutover import (ADR 0014). */
    @Column(name = "pppoe_username", nullable = false)
    private String pppoeUsername;

    /** True when the import kept the old system's username; only such a name may be corrected, while the window is open. */
    @Column(name = "username_kept", nullable = false)
    private boolean usernameKept;

    @JdbcTypeCode(SqlTypes.INET)
    @Column(name = "static_ip")
    private InetAddress staticIp;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SubscriptionState state;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "grace_days", nullable = false)
    private int graceDays;

    /** The tenant's grace mode when the cycle was sold (S6); null on lines renewed before it was kept. */
    @Column(name = "grace_mode")
    @Enumerated(EnumType.STRING)
    private GraceMode graceMode;

    @Column(name = "paused_at")
    private Instant pausedAt;

    @Column(name = "pause_until")
    private Instant pauseUntil;

    @Column(name = "activated_at")
    private Instant activatedAt;

    /** S12: why the line left PENDING; null until then (and on lines cancelled before activation). */
    @Column(name = "activation_cause")
    @Enumerated(EnumType.STRING)
    private ActivationCause activationCause;

    /** When the current cycle began: the last renewal (Q12 default); null until the first renewal after V202610211000. */
    @Column(name = "cycle_started_at")
    private Instant cycleStartedAt;

    @Column(name = "terminated_at")
    private Instant terminatedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Subscription() {}

    /**
     * A new PENDING line (REJECT until its first renewal). {@code grace_days} stays 0 until the
     * first renewal copies it from the package or tenant (S6).
     */
    public static Subscription open(
            final UUID id,
            final UUID tenantId,
            final UUID customerId,
            final UUID packageId,
            final UUID routerId,
            final String pppoeUsername,
            final boolean usernameKept,
            final Instant now) {
        final Subscription line = new Subscription();
        line.id = id;
        line.tenantId = tenantId;
        line.customerId = customerId;
        line.packageId = packageId;
        line.routerId = routerId;
        line.pppoeUsername = pppoeUsername;
        line.usernameKept = usernameKept;
        line.state = SubscriptionState.PENDING;
        line.graceDays = 0;
        line.createdAt = now;
        line.updatedAt = now;
        return line;
    }

    public AccessPolicy.Facts facts() {
        return new AccessPolicy.Facts(state, expiresAt, graceDays, pausedAt, pauseUntil);
    }

    /**
     * S5, S6: an early renewal extends from the current expiry, so no paid time is lost; a line past
     * grace (or a first activation) starts from {@code now}. The clock time of the base is kept
     * because the period is a whole number of minutes added to an instant (Q6 default: exact
     * timestamps). {@code graceDays} and {@code graceMode} are copied for the new cycle (S6), and the package the cycle was sold
     * for becomes the line's package, clearing a pending change (S10). Every renewal starts a new
     * cycle for fair use and data caps at {@code now}, so its allowance resets (Q12, Q13 defaults).
     */
    public Renewal renew(final Duration period, final int graceDays, final GraceMode graceMode, final UUID packageId,
                         final Instant now) {
        requireRenewable();
        final SubscriptionState from = state;
        final Instant previousExpiresAt = expiresAt;
        final Instant base;
        if (state == SubscriptionState.PENDING) {
            base = now;
            activate(ActivationCause.RENEWAL, now);
        } else {
            final Instant graceEndsAt = expiresAt.plus(Duration.ofDays(this.graceDays));
            base = now.isBefore(graceEndsAt) ? expiresAt : now;
        }
        expiresAt = base.plus(period);
        this.graceDays = graceDays;
        this.graceMode = graceMode;
        this.packageId = packageId;
        pendingPackageId = null;
        cycleStartedAt = now;
        updatedAt = now;
        return new Renewal(from, previousExpiresAt, expiresAt);
    }

    /**
     * S11 (ADR 0014): the cutover import opens the line ACTIVE with the expiry the customer already
     * paid for in the old system, with no payment on the platform. The fair-use cycle began one
     * package duration before it (N9); grace is copied as a renewal would copy it (S6). A past
     * {@code paidUntil} is allowed: the line is ACTIVE and expired, so RADIUS puts the customer on the
     * pay page (S2) instead of rejecting them. At most one package duration ahead.
     */
    public Transition activateByImport(final Instant paidUntil, final Duration period, final int graceDays,
                                       final GraceMode graceMode, final Instant now) {
        if (state != SubscriptionState.PENDING) {
            throw new LifecycleViolation("S11: only a PENDING line can be activated by the import; this one is " + state + ".");
        }
        if (paidUntil.isAfter(now.plus(period))) {
            throw new LifecycleViolation("S11: paid_until is more than one package duration ahead.");
        }
        activate(ActivationCause.IMPORTED, now);
        expiresAt = paidUntil;
        cycleStartedAt = paidUntil.minus(period);
        this.graceDays = graceDays;
        this.graceMode = graceMode;
        updatedAt = now;
        return new Transition(SubscriptionState.PENDING, SubscriptionState.ACTIVE, false);
    }

    /**
     * S12, ADR 0019: a line of the probe tenant opens ACTIVE with the expiry the probe needs, far ahead
     * (expected FULL) or long past (expected RESTRICTED, no grace). No payment and no ledger entry; only the
     * platform's probe setup calls it, and only for {@link ProbeTenant}, which
     * the database checks too.
     */
    public Transition activateForProbe(final Instant expiresAt, final Instant now) {
        if (!ProbeTenant.is(tenantId)) {
            throw new LifecycleViolation("S12: PROBE activates only lines of the probe tenant.");
        }
        if (state != SubscriptionState.PENDING) {
            throw new LifecycleViolation("S12: only a PENDING line can be activated for the probe; this one is " + state + ".");
        }
        activate(ActivationCause.PROBE, now);
        this.expiresAt = expiresAt;
        cycleStartedAt = now;
        graceDays = 0;
        graceMode = GraceMode.FULL_SPEED;
        updatedAt = now;
        return new Transition(SubscriptionState.PENDING, SubscriptionState.ACTIVE, false);
    }

    /**
     * S12: the one place a line leaves PENDING for ACTIVE, reached only from {@link #renew},
     * {@link #activateByImport} and {@link #activateForProbe} (Subscription12Test, ActivationCauseIT).
     */
    private void activate(final ActivationCause cause, final Instant now) {
        if (state != SubscriptionState.PENDING) {
            throw new LifecycleViolation("S12: only a PENDING line is activated; this one is " + state + ".");
        }
        state = SubscriptionState.ACTIVE;
        activatedAt = now;
        activationCause = cause;
    }

    /**
     * A username the cutover import kept from the old system was typed wrong (ADR 0014). Only a kept
     * name changes; generated ones never do (T2). The caller checks the cutover window.
     */
    public void correctKeptUsername(final String username, final Instant now) {
        if (!usernameKept) {
            throw new LifecycleViolation("T2: a generated PPPoE username never changes; only one kept from the old system can be corrected.");
        }
        pppoeUsername = username;
        updatedAt = now;
    }

    /** The package the next renewal sells: a pending change, else the current package (S10). */
    public UUID packageForNextRenewal() {
        return pendingPackageId != null ? pendingPackageId : packageId;
    }

    /**
     * S10: an immediate change (upgrade, paid by proration) during the paid period, before expiry.
     * The expiry does not move.
     */
    public void changePackageNow(final UUID newPackageId, final Instant now) {
        if (state != SubscriptionState.ACTIVE || !now.isBefore(expiresAt)) {
            throw new LifecycleViolation("S10: an immediate package change needs an ACTIVE line before its expiry; "
                    + "change it at the next renewal instead.");
        }
        packageId = newPackageId;
        pendingPackageId = null;
        updatedAt = now;
    }

    /** S10: a downgrade, or an upgrade the customer takes at the next cycle. */
    public void changePackageAtRenewal(final UUID newPackageId, final Instant now) {
        if (state == SubscriptionState.TERMINATED) {
            throw new LifecycleViolation("A TERMINATED line has no next renewal.");
        }
        pendingPackageId = newPackageId.equals(packageId) ? null : newPackageId;
        updatedAt = now;
    }

    /**
     * Paused, suspended and terminated lines are not renewed; a payment for one stays in the wallet
     * until the line can be renewed (B5).
     */
    public void requireRenewable() {
        if (state != SubscriptionState.PENDING && state != SubscriptionState.ACTIVE) {
            throw new LifecycleViolation("S5: only a PENDING or ACTIVE line can be renewed; this one is " + state + ".");
        }
    }

    /** S7: only an ACTIVE line with time remaining, for at most the tenant's limit. */
    public Transition pause(final Instant until, final Instant now, final int maxPauseDays) {
        if (state != SubscriptionState.ACTIVE) {
            throw new LifecycleViolation("S7: only an ACTIVE line can be paused; this one is " + state + ".");
        }
        if (!now.isBefore(expiresAt)) {
            throw new LifecycleViolation("S7: only a line with paid time remaining can be paused.");
        }
        if (!until.isAfter(now)) {
            throw new LifecycleViolation("S7: the pause must end in the future.");
        }
        if (Duration.between(now, until).compareTo(Duration.ofDays(maxPauseDays)) > 0) {
            throw new LifecycleViolation("S7: a pause lasts at most " + maxPauseDays + " days.");
        }
        final Transition transition = new Transition(state, SubscriptionState.PAUSED, false);
        state = SubscriptionState.PAUSED;
        pausedAt = now;
        pauseUntil = until;
        updatedAt = now;
        return transition;
    }

    /**
     * S7: the paused time is given back. Before {@code pause_until} that is {@code now - paused_at};
     * from {@code pause_until} on, the view has already resumed the line with
     * {@code pause_until - paused_at}, so the same shift is recorded and the transition is marked as
     * recorded, not caused, here.
     */
    public Transition resume(final Instant now) {
        if (state != SubscriptionState.PAUSED) {
            throw new LifecycleViolation("S7: only a PAUSED line can be resumed; this one is " + state + ".");
        }
        final boolean alreadyResumed = !now.isBefore(pauseUntil);
        endPause(now);
        state = SubscriptionState.ACTIVE;
        updatedAt = now;
        return new Transition(SubscriptionState.PAUSED, SubscriptionState.ACTIVE, alreadyResumed);
    }

    /**
     * Admin hold (docs/04). A paused line first gets its paused time back, so lifting the hold later
     * does not lose it; dates keep running during the hold (S8).
     */
    public Transition suspend(final Instant now) {
        if (state != SubscriptionState.ACTIVE && state != SubscriptionState.PAUSED) {
            throw new LifecycleViolation("Only an ACTIVE or PAUSED line can be suspended; this one is " + state + ".");
        }
        final Transition transition = new Transition(state, SubscriptionState.SUSPENDED, false);
        if (state == SubscriptionState.PAUSED) {
            endPause(now);
        }
        state = SubscriptionState.SUSPENDED;
        updatedAt = now;
        return transition;
    }

    /** S8: dates are re-evaluated, so a line that expired during the hold comes back RESTRICTED. */
    public Transition liftSuspension(final Instant now) {
        if (state != SubscriptionState.SUSPENDED) {
            throw new LifecycleViolation("S8: only a SUSPENDED line can have its hold lifted; this one is " + state + ".");
        }
        state = SubscriptionState.ACTIVE;
        updatedAt = now;
        return new Transition(SubscriptionState.SUSPENDED, SubscriptionState.ACTIVE, false);
    }

    /** Closes the line and frees its static IP; customer, ledger and accounting history remain. */
    public Transition terminate(final Instant now) {
        if (state != SubscriptionState.ACTIVE && state != SubscriptionState.SUSPENDED) {
            throw new LifecycleViolation("Only an ACTIVE or SUSPENDED line can be terminated; this one is " + state + ".");
        }
        final Transition transition = new Transition(state, SubscriptionState.TERMINATED, false);
        state = SubscriptionState.TERMINATED;
        terminatedAt = now;
        staticIp = null;
        updatedAt = now;
        return transition;
    }

    /**
     * Cancel before activation (docs/04, decisions.md 2026-10-21): a PENDING line that was never paid
     * closes for good. Nothing was charged, so the wallet is left as it is.
     */
    public Transition cancelBeforeActivation(final Instant now) {
        if (state != SubscriptionState.PENDING) {
            throw new LifecycleViolation("Only a PENDING line can be cancelled before activation; this one is " + state
                    + ". Terminate an active line instead.");
        }
        final Transition transition = new Transition(state, SubscriptionState.TERMINATED, false);
        state = SubscriptionState.TERMINATED;
        terminatedAt = now;
        staticIp = null;
        updatedAt = now;
        return transition;
    }

    private void endPause(final Instant now) {
        final Instant end = now.isBefore(pauseUntil) ? now : pauseUntil;
        expiresAt = expiresAt.plus(Duration.between(pausedAt, end));
        pausedAt = null;
        pauseUntil = null;
    }

    /** {@code recorded}: the change already took effect in the RADIUS view before this transition. */
    public record Transition(SubscriptionState from, SubscriptionState to, boolean recorded) {}

    public record Renewal(SubscriptionState from, Instant previousExpiresAt, Instant expiresAt) {}

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public UUID getCustomerId() {
        return customerId;
    }

    public UUID getPackageId() {
        return packageId;
    }

    public UUID getRouterId() {
        return routerId;
    }

    public UUID getPendingPackageId() {
        return pendingPackageId;
    }

    public String getPppoeUsername() {
        return pppoeUsername;
    }

    public boolean isUsernameKept() {
        return usernameKept;
    }

    public InetAddress getStaticIp() {
        return staticIp;
    }

    public SubscriptionState getState() {
        return state;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public int getGraceDays() {
        return graceDays;
    }

    public Instant getPausedAt() {
        return pausedAt;
    }

    public Instant getPauseUntil() {
        return pauseUntil;
    }

    public Instant getActivatedAt() {
        return activatedAt;
    }

    /** The grace mode this cycle was sold with, or {@code tenantMode} for a line renewed before it was kept. */
    public GraceMode graceModeOr(final GraceMode tenantMode) {
        return graceMode != null ? graceMode : tenantMode;
    }

    public ActivationCause getActivationCause() {
        return activationCause;
    }

    public Instant getCycleStartedAt() {
        return cycleStartedAt;
    }

    public Instant getTerminatedAt() {
        return terminatedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
