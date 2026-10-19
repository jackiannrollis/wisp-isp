package com.corebalt.isp.identity.application;

import com.corebalt.isp.shared.audit.AuditEntry;
import com.corebalt.isp.shared.tenant.TenantContext;
import com.corebalt.isp.shared.web.ConflictException;
import com.corebalt.isp.shared.web.InvalidRequestException;
import com.corebalt.isp.tenancy.api.TenantRegistry;
import com.corebalt.isp.tenancy.api.TenantRegistry.TenantRef;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Tenant onboarding from the platform console (docs/07 step 1): the tenant in TRIAL, its built-in
 * roles, and its first staff member invited as Owner with a temporary password shown once.
 */
@Service
public class PlatformTenants {

    private static final Pattern CODE = Pattern.compile("^[A-Z]{2,4}$");
    private static final Pattern SUBDOMAIN = Pattern.compile("^[a-z0-9]([a-z0-9-]{0,30}[a-z0-9])?$");

    public record CreateTenant(String code, String name, String subdomain, String ownerEmail, String ownerName,
                               String ownerPhone) {}

    public record Created(TenantRef tenant, StaffAdmin.Invitation owner) {}

    private final TenantRegistry tenants;
    private final Roles roles;
    private final StaffAdmin staff;
    private final JdbcAuditTrail tenantAudit;
    private final PlatformAudit platformAudit;
    private final TransactionTemplate tx;

    PlatformTenants(final TenantRegistry tenants, final Roles roles, final StaffAdmin staff, final JdbcAuditTrail tenantAudit,
                    final PlatformAudit platformAudit, final TransactionTemplate tx) {
        this.tenants = tenants;
        this.roles = roles;
        this.staff = staff;
        this.tenantAudit = tenantAudit;
        this.platformAudit = platformAudit;
        this.tx = tx;
    }

    public List<TenantRef> list() {
        return tenants.list();
    }

    public Created create(final UUID adminId, final CreateTenant request) {
        final String code = request.code() == null ? "" : request.code().strip().toUpperCase(Locale.ROOT);
        final String subdomain = request.subdomain() == null ? "" : request.subdomain().strip().toLowerCase(Locale.ROOT);
        if (!CODE.matcher(code).matches()) {
            throw InvalidRequestException.of("code", "2 to 4 letters; it prefixes every customer number (T2)");
        }
        if (!SUBDOMAIN.matcher(subdomain).matches()) {
            throw InvalidRequestException.of("subdomain", "lower-case letters, digits and hyphens");
        }
        if (request.name() == null || request.name().isBlank()) {
            throw InvalidRequestException.of("name", "required");
        }
        // Checked before the tenant exists: the registry commits on its own, so a bad owner must not
        // leave a tenant behind with its code and subdomain taken.
        StaffAdmin.validate(new StaffAdmin.InviteRequest(request.ownerEmail(), request.ownerName(), request.ownerPhone(), null),
                "owner");
        if (tenants.findByCodeOrSubdomain(code).isPresent() || tenants.findByCodeOrSubdomain(subdomain).isPresent()) {
            throw new ConflictException("A tenant with this code or subdomain already exists.");
        }
        final TenantRef tenant = TenantContext.callAsPlatform("tenant-onboarding",
                () -> tenants.create(code, request.name().strip(), subdomain));
        final StaffAdmin.Invitation owner = TenantContext.callAsTenant(tenant.id(), () -> tx.execute(s -> {
            final UUID ownerRole = roles.ensureBuiltIns();
            final StaffAdmin.Invitation invitation = staff.invite(new StaffAdmin.InviteRequest(request.ownerEmail(),
                    request.ownerName(), request.ownerPhone(), ownerRole));
            tenantAudit.recordPlatform(adminId, null, AuditEntry.of("identity.tenant_created", "tenant", tenant.id(),
                    Map.of("code", code, "ownerStaffId", invitation.staff().id().toString())));
            platformAudit.record(adminId, "platform.tenant_created", "tenant", tenant.id(), Map.of("code", code));
            return invitation;
        }));
        return new Created(tenant, owner);
    }
}
