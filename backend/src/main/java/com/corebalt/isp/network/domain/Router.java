package com.corebalt.isp.network.domain;

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
}
