package com.corebalt.isp.network.domain;

import com.corebalt.isp.shared.web.ConflictException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.net.InetAddress;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A RADIUS NAS (docs/03 network), minimal for step 3: its name and tunnel address on hub A, by which
 * FreeRADIUS recognises it. The shared secret is in {@code network_radius_credential} (ADR 0009).
 */
@Entity
@Table(name = "network_router")
public class Router {

    @Id private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private String name;

    @JdbcTypeCode(SqlTypes.INET)
    @Column(name = "tunnel_ip_a", nullable = false)
    private InetAddress tunnelIpA;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    // Monitoring (docs/06 Monitoring): written by the tunnel worker and the accounting job with SQL,
    // read here only, so saving the entity never overwrites them.
    @Column(name = "status", insertable = false, updatable = false)
    private String status;

    @Column(name = "rest_username", insertable = false, updatable = false)
    private String restUsername;

    @Column(name = "last_seen_at", insertable = false, updatable = false)
    private Instant lastSeenAt;

    @Column(name = "offline_since", insertable = false, updatable = false)
    private Instant offlineSince;

    @Column(name = "last_polled_at", insertable = false, updatable = false)
    private Instant lastPolledAt;

    @Column(name = "last_poll_error", insertable = false, updatable = false)
    private String lastPollError;

    @Column(name = "last_accounting_at", insertable = false, updatable = false)
    private Instant lastAccountingAt;

    @Column(name = "routeros_version", insertable = false, updatable = false)
    private String routerosVersion;

    @Column(name = "board_name", insertable = false, updatable = false)
    private String boardName;

    @Column(name = "uptime_seconds", insertable = false, updatable = false)
    private Long uptimeSeconds;

    @Column(name = "cpu_load", insertable = false, updatable = false)
    private Integer cpuLoad;
    // Onboarding (docs/06, ADR 0013): the router's WireGuard public key (the pair is generated here,
    // the private key handed over once in the script) and the ISP's addressing the script needs.
    @Column(name = "wg_public_key")
    private String wgPublicKey;
    @Column(name = "pppoe_interface")
    private String pppoeInterface;
    @JdbcTypeCode(SqlTypes.INET)
    @Column(name = "ppp_local_address")
    private InetAddress pppLocalAddress;
    @Column(name = "pool_full")
    private String poolFull;
    @Column(name = "pool_restricted")
    private String poolRestricted;
    @Column(name = "management_cidrs")
    private String managementCidrs;
    @Column(name = "onboarding_script_generated_at")
    private Instant onboardingScriptGeneratedAt;
    // Retirement (ADR 0015, N10): final; the row stays for history.
    @Column(name = "retired_at")
    private Instant retiredAt;
    @Column(name = "retired_reason")
    private String retiredReason;
    @Column(name = "replaced_by_router_id")
    private UUID replacedByRouterId;

    protected Router() {}

    public static Router register(
            final UUID id, final UUID tenantId, final String name, final InetAddress tunnelIpA, final Instant now) {
        final Router router = new Router();
        router.id = id;
        router.tenantId = tenantId;
        router.name = name;
        router.tunnelIpA = tunnelIpA;
        router.createdAt = now;
        router.updatedAt = now;
        return router;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public String getName() {
        return name;
    }

    public InetAddress getTunnelIpA() {
        return tunnelIpA;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /** What an onboarding script was generated with; the new WireGuard public key replaces the old at once. */
    public void onboard(
            final String wgPublicKey, final String pppoeInterface, final InetAddress pppLocalAddress, final String poolFull,
            final String poolRestricted, final String managementCidrs, final Instant now) {
        this.wgPublicKey = wgPublicKey;
        this.pppoeInterface = pppoeInterface;
        this.pppLocalAddress = pppLocalAddress;
        this.poolFull = poolFull;
        this.poolRestricted = poolRestricted;
        this.managementCidrs = managementCidrs;
        this.onboardingScriptGeneratedAt = now;
        this.updatedAt = now;
    }

    /**
     * N10: the router leaves the platform for good. {@code replacement} takes its lines, when it had any;
     * the caller revokes the secret, clears the REST login and closes its sessions in the same transaction.
     */
    public void retire(final String reason, final UUID replacement, final Instant now) {
        requireActive();
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("N10: a reason is required");
        }
        if (id.equals(replacement)) {
            throw new ConflictException("N10: a router cannot replace itself; choose another router for its lines.");
        }
        retiredAt = now;
        retiredReason = reason.strip();
        replacedByRouterId = replacement;
        updatedAt = now;
    }

    /** N10: a retired router takes no new lines, secrets, scripts, logins or polls. */
    public void requireActive() {
        if (retiredAt != null) {
            throw new RouterRetiredException(name);
        }
    }

    public boolean isRetired() {
        return retiredAt != null;
    }

    public Instant getRetiredAt() {
        return retiredAt;
    }

    public String getRetiredReason() {
        return retiredReason;
    }

    public UUID getReplacedByRouterId() {
        return replacedByRouterId;
    }

    public String getWgPublicKey() {
        return wgPublicKey;
    }

    public String getPppoeInterface() {
        return pppoeInterface;
    }

    public InetAddress getPppLocalAddress() {
        return pppLocalAddress;
    }

    public String getPoolFull() {
        return poolFull;
    }

    public String getPoolRestricted() {
        return poolRestricted;
    }

    public String getManagementCidrs() {
        return managementCidrs;
    }

    public Instant getOnboardingScriptGeneratedAt() {
        return onboardingScriptGeneratedAt;
    }

    public String getStatus() {
        return status == null ? "NOT_MONITORED" : status;
    }

    public String getRestUsername() {
        return restUsername;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }

    public Instant getOfflineSince() {
        return offlineSince;
    }

    public Instant getLastPolledAt() {
        return lastPolledAt;
    }

    public String getLastPollError() {
        return lastPollError;
    }

    public Instant getLastAccountingAt() {
        return lastAccountingAt;
    }

    public String getRouterosVersion() {
        return routerosVersion;
    }

    public String getBoardName() {
        return boardName;
    }

    public Long getUptimeSeconds() {
        return uptimeSeconds;
    }

    public Integer getCpuLoad() {
        return cpuLoad;
    }
}
