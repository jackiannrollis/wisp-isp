package com.corebalt.isp.subscriptions.domain;

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

    @Column(name = "pppoe_username", nullable = false, updatable = false)
    private String pppoeUsername;

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

    @Column(name = "paused_at")
    private Instant pausedAt;

    @Column(name = "pause_until")
    private Instant pauseUntil;

    @Column(name = "activated_at")
    private Instant activatedAt;

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
            final Instant now) {
        final Subscription line = new Subscription();
        line.id = id;
        line.tenantId = tenantId;
        line.customerId = customerId;
        line.packageId = packageId;
        line.routerId = routerId;
        line.pppoeUsername = pppoeUsername;
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
     * timestamps). {@code graceDays} is copied for the new cycle, and the package the cycle was sold
     * for becomes the line's package, clearing a pending change (S10). Every renewal starts a new
     * cycle for fair use and data caps at {@code now}, so its allowance resets (Q12, Q13 defaults).
     */
    public Renewal renew(final Duration period, final int graceDays, final UUID packageId, final Instant now) {
        requireRenewable();
        final SubscriptionState from = state;
        final Instant previousExpiresAt = expiresAt;
        final Instant base;
        if (state == SubscriptionState.PENDING) {
            base = now;
            activatedAt = now;
        } else {
            final Instant graceEndsAt = expiresAt.plus(Duration.ofDays(this.graceDays));
            base = now.isBefore(graceEndsAt) ? expiresAt : now;
        }
        state = SubscriptionState.ACTIVE;
        expiresAt = base.plus(period);
        this.graceDays = graceDays;
        this.packageId = packageId;
        pendingPackageId = null;
        cycleStartedAt = now;
        updatedAt = now;
        return new Renewal(from, previousExpiresAt, expiresAt);
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
