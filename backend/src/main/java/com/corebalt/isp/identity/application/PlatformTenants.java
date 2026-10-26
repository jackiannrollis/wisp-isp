package com.corebalt.isp.identity.application;

import com.corebalt.isp.shared.audit.AuditEntry;
import com.corebalt.isp.shared.tenant.TenantContext;
import com.corebalt.isp.shared.web.ConflictException;
import com.corebalt.isp.shared.web.InvalidRequestException;
import com.corebalt.isp.shared.web.NotFoundException;
import com.corebalt.isp.tenancy.api.TenantRegistry;
import com.corebalt.isp.tenancy.api.TenantRegistry.TenantRef;
import java.time.Instant;
import java.util.HashMap;
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

    /** TRIAL to ACTIVE (docs/07 Platform layer). */
    public TenantRef activate(final UUID adminId, final UUID tenantId) {
        final TenantRef before = tenant(tenantId);
        final TenantRef after = tenants.activate(tenantId);
        auditStatus(adminId, before, after, "activated", null);
        return after;
    }

    /**
     * Suspends a tenant with a reason: staff and portal sign-ins are refused and scheduled jobs skip
     * the tenant; its callbacks, events and RADIUS logins continue (docs/07), so cutting the ISP's
     * customers off is a separate decision, not taken here.
     */
    public TenantRef suspend(final UUID adminId, final UUID tenantId, final String reason) {
        if (reason == null || reason.isBlank()) {
            throw InvalidRequestException.of("reason", "required");
        }
        final TenantRef before = tenant(tenantId);
        final TenantRef after = tenants.suspend(tenantId);
        auditStatus(adminId, before, after, "suspended", reason.strip());
        return after;
    }

    /** SUSPENDED to ACTIVE. */
    public TenantRef reinstate(final UUID adminId, final UUID tenantId) {
        final TenantRef before = tenant(tenantId);
        final TenantRef after = tenants.reinstate(tenantId);
        auditStatus(adminId, before, after, "reinstated", null);
        return after;
    }

    /**
     * A new temporary password for an invitation that was never used (docs/07 onboarding step 1: the
     * Owner lost theirs before the first sign-in), shown once; an active member cannot be reset from
     * here. Audited in the tenant's log (A1) and the platform's.
     */
    public StaffAdmin.Invitation reissueInvitation(final UUID adminId, final UUID tenantId, final UUID staffId) {
        final TenantRef tenant = tenant(tenantId);
        return TenantContext.callAsTenant(tenant.id(), () -> tx.execute(s -> {
            final StaffAdmin.Invitation invitation = staff.reissueInvitation(staffId);
            tenantAudit.recordPlatform(adminId, null, AuditEntry.of("identity.invitation_regenerated", "staff_user", staffId,
                    Map.of("email", invitation.staff().email())));
            platformAudit.record(adminId, "platform.invitation_reissued", "staff_user", staffId,
                    Map.of("tenantId", tenant.id().toString(), "code", tenant.code()));
            return invitation;
        }));
    }

    /**
     * Opens the tenant's cutover window until {@code until}, at most 30 days ahead (ADR 0014, S11):
     * until then the tenant's Owner may import lines already active with the expiry their customers
     * paid for in the old system. A second call moves the end. Audited in both logs.
     */
    public TenantRef openCutoverWindow(final UUID adminId, final UUID tenantId, final Instant until) {
        final TenantRef before = tenant(tenantId);
        final TenantRef after = tenants.openCutoverWindow(tenantId, until);
        auditCutoverWindow(adminId, before, after, "opened");
        return after;
    }

    /** Closes the cutover window before its end. */
    public TenantRef closeCutoverWindow(final UUID adminId, final UUID tenantId) {
        final TenantRef before = tenant(tenantId);
        final TenantRef after = tenants.closeCutoverWindow(tenantId);
        auditCutoverWindow(adminId, before, after, "closed");
        return after;
    }

    private TenantRef tenant(final UUID tenantId) {
        return tenants.find(tenantId).orElseThrow(() -> new NotFoundException("Tenant"));
    }

    private void auditCutoverWindow(final UUID adminId, final TenantRef before, final TenantRef after, final String verb) {
        final Map<String, Object> was = new HashMap<>();
        was.put("cutoverWindowUntil", before.cutoverWindowUntil() == null ? null : before.cutoverWindowUntil().toString());
        final Map<String, Object> is = new HashMap<>();
        is.put("cutoverWindowUntil", after.cutoverWindowUntil() == null ? null : after.cutoverWindowUntil().toString());
        TenantContext.callAsTenant(after.id(), () -> tx.execute(s -> {
            tenantAudit.recordPlatform(adminId, null, new AuditEntry("identity.cutover_window_" + verb, "tenant", after.id(),
                    was, is, null));
            final Map<String, Object> detail = new HashMap<>(is);
            detail.put("code", after.code());
            platformAudit.record(adminId, "platform.cutover_window_" + verb, "tenant", after.id(), detail);
            return null;
        }));
    }

    private void auditStatus(final UUID adminId, final TenantRef before, final TenantRef after, final String verb,
                             final String reason) {
        TenantContext.callAsTenant(after.id(), () -> tx.execute(s -> {
            tenantAudit.recordPlatform(adminId, null, new AuditEntry("identity.tenant_" + verb, "tenant", after.id(),
                    Map.of("status", before.status()), Map.of("status", after.status()), reason));
            platformAudit.record(adminId, "platform.tenant_" + verb, "tenant", after.id(),
                    reason == null ? Map.of("code", after.code(), "status", after.status())
                            : Map.of("code", after.code(), "status", after.status(), "reason", reason));
            return null;
        }));
    }
}
