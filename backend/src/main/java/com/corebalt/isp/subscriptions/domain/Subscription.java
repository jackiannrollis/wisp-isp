package com.corebalt.isp.subscriptions.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A service line (docs/03 subscriptions), minimal for step 3: who, which package, which router, the
 * PPPoE username and the stored state (S1). The lifecycle (activation, renewal, pause, suspension,
 * termination) and AccessPolicy arrive in step 4; until then a line is created PENDING. Other
 * modules' rows are referenced by ID only.
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

    @Column(name = "pppoe_username", nullable = false, updatable = false)
    private String pppoeUsername;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SubscriptionState state;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "grace_days", nullable = false)
    private int graceDays;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Subscription() {}

    /**
     * A new PENDING line (REJECT until activated). {@code grace_days} stays 0: it is copied from the
     * package or tenant at activation and each renewal (S6), which step 4 implements.
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

    public String getPppoeUsername() {
        return pppoeUsername;
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

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
