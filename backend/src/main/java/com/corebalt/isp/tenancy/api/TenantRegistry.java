package com.corebalt.isp.tenancy.api;

import java.time.Instant;
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

    /** TRIAL to ACTIVE (docs/07 Platform layer); anything else is a conflict. */
    TenantRef activate(UUID tenantId);

    /**
     * TRIAL or ACTIVE to SUSPENDED. What a suspension does is exactly what reads this status: staff
     * and portal sign-ins are refused ({@link TenantRef#canSignIn()}) and scheduled jobs skip the
     * tenant ({@code TenantDirectory}); callbacks, events and RADIUS logins continue, so the ISP's
     * customers stay online (docs/07).
     */
    TenantRef suspend(UUID tenantId);

    /** SUSPENDED to ACTIVE (a suspended trial comes back as ACTIVE). */
    TenantRef reinstate(UUID tenantId);

    /**
     * Opens (or moves) the tenant's cutover window (ADR 0014, S11): until {@code until}, at most 30
     * days ahead, the customer import may activate lines with the expiry their customers already
     * paid for. The window closes by itself at that instant.
     */
    TenantRef openCutoverWindow(UUID tenantId, Instant until);

    /** Closes the cutover window now; a no-op when none is open. */
    TenantRef closeCutoverWindow(UUID tenantId);

    record TenantRef(UUID id, String code, String name, String subdomain, String status, Instant cutoverWindowUntil) {

        public boolean canSignIn() {
            return !"SUSPENDED".equals(status);
        }

        public boolean cutoverWindowOpen(final Instant now) {
            return cutoverWindowUntil != null && now.isBefore(cutoverWindowUntil);
        }
    }
}
