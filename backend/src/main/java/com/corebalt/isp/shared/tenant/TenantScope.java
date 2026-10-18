package com.corebalt.isp.shared.tenant;

import java.util.Objects;
import java.util.UUID;

/** What a unit of work runs as: exactly one tenant, or the explicit, logged platform scope. */
public sealed interface TenantScope {

    record Tenant(UUID tenantId) implements TenantScope {
        public Tenant {
            Objects.requireNonNull(tenantId, "tenantId");
        }
    }

    /**
     * Platform scope for code that must see every tenant (schedulers, the outbox relay, the platform
     * console). No {@code app.tenant_id} is set, so row-level security returns no tenant rows; such
     * code reaches tenant data only through {@link TenantIterator}.
     */
    record Platform(String reason) implements TenantScope {
        public Platform {
            Objects.requireNonNull(reason, "reason");
        }
    }
}
