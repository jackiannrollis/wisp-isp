package com.corebalt.isp.tenancy.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The tenant registry for logins (a staff member or customer names their ISP by code or subdomain)
 * and for the platform console. Platform-scoped: the registry has no row-level security.
 */
public interface TenantRegistry {

    Optional<TenantRef> find(UUID tenantId);

    /** Case-insensitive code ({@code CB}) or subdomain ({@code acme}). */
    Optional<TenantRef> findByCodeOrSubdomain(String codeOrSubdomain);

    List<TenantRef> list();

    /** A new tenant in TRIAL (docs/07 onboarding step 1). */
    TenantRef create(String code, String name, String subdomain);

    record TenantRef(UUID id, String code, String name, String subdomain, String status) {

        public boolean canSignIn() {
            return !"SUSPENDED".equals(status);
        }
    }
}
