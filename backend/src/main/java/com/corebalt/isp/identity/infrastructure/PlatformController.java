package com.corebalt.isp.identity.infrastructure;

import com.corebalt.isp.identity.application.Impersonations;
import com.corebalt.isp.identity.application.PlatformTenants;
import com.corebalt.isp.identity.application.StaffAdmin;
import com.corebalt.isp.shared.security.CurrentActor;
import com.corebalt.isp.shared.tenant.TenantContext;
import com.corebalt.isp.shared.web.PageRequests;
import com.corebalt.isp.shared.web.PageResponse;
import com.corebalt.isp.tenancy.api.TenantRegistry.TenantRef;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The platform console API (PLATFORM tokens only): tenants and impersonation. */
@RestController
@RequestMapping("/api/platform")
@Tag(name = "Platform")
class PlatformController {

    record ImpersonationRequest(@NotNull UUID staffUserId, @NotBlank String reason, Integer minutes) {}

    record SuspendRequest(@NotBlank @Size(max = 500) String reason) {}

    private final PlatformTenants tenants;
    private final StaffAdmin staff;
    private final Impersonations impersonations;

    PlatformController(final PlatformTenants tenants, final StaffAdmin staff, final Impersonations impersonations) {
        this.tenants = tenants;
        this.staff = staff;
        this.impersonations = impersonations;
    }

    @GetMapping("/tenants")
    List<TenantRef> tenants() {
        return tenants.list();
    }

    @PostMapping("/tenants")
    @Operation(summary = "Onboard a tenant in TRIAL with its built-in roles; the Owner's temporary password is shown once")
    ResponseEntity<PlatformTenants.Created> create(@RequestBody final PlatformTenants.CreateTenant request) {
        final PlatformTenants.Created created = tenants.create(admin(), request);
        return ResponseEntity.created(URI.create("/api/platform/tenants/" + created.tenant().id())).body(created);
    }

    @PostMapping("/tenants/{tenantId}/activate")
    @Operation(summary = "TRIAL to ACTIVE")
    TenantRef activate(@PathVariable final UUID tenantId) {
        return tenants.activate(admin(), tenantId);
    }

    @PostMapping("/tenants/{tenantId}/suspend")
    @Operation(summary = "Suspend with a reason: staff and portal sign-ins are refused and scheduled jobs skip the tenant; "
            + "callbacks, events and RADIUS logins continue, so its customers stay online")
    TenantRef suspend(@PathVariable final UUID tenantId, @Valid @RequestBody final SuspendRequest request) {
        return tenants.suspend(admin(), tenantId, request.reason());
    }

    @PostMapping("/tenants/{tenantId}/reinstate")
    @Operation(summary = "SUSPENDED to ACTIVE")
    TenantRef reinstate(@PathVariable final UUID tenantId) {
        return tenants.reinstate(admin(), tenantId);
    }

    @PostMapping("/tenants/{tenantId}/staff/{staffId}/invitation")
    @Operation(summary = "Reissue an invitation that was never used (an Owner who lost theirs): a new temporary password, "
            + "shown only in this answer, valid 72 hours; an active member is reset by the tenant's own staff")
    ResponseEntity<StaffAdmin.Invitation> reissueInvitation(@PathVariable final UUID tenantId, @PathVariable final UUID staffId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(tenants.reissueInvitation(admin(), tenantId, staffId));
    }

    @GetMapping("/tenants/{tenantId}/staff")
    @Operation(summary = "A tenant's staff, to choose whom to impersonate")
    PageResponse<StaffAdmin.StaffDto> staff(@PathVariable final UUID tenantId) {
        return TenantContext.callAsTenant(tenantId, () -> staff.list("ACTIVE", null,
                PageRequests.of(0, 200, null, Set.of(), Sort.by("createdAt"))));
    }

    @PostMapping("/tenants/{tenantId}/impersonations")
    @Operation(summary = "Act as a tenant staff member for at most 30 minutes; recorded in the tenant's audit log")
    Impersonations.Started impersonate(@PathVariable final UUID tenantId, @Valid @RequestBody final ImpersonationRequest request) {
        return impersonations.start(admin(), tenantId, request.staffUserId(), request.reason(), request.minutes());
    }

    @GetMapping("/impersonations")
    List<Impersonations.ImpersonationDto> recent() {
        return impersonations.recent();
    }

    @PostMapping("/impersonations/{id}/end")
    @Operation(summary = "End an impersonation; its token stops working at the next request")
    Impersonations.ImpersonationDto end(@PathVariable final UUID id) {
        return impersonations.end(admin(), id);
    }

    private static UUID admin() {
        return CurrentActor.platformAdminId().orElseThrow();
    }
}
