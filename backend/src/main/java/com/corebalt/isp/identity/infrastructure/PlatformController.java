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
import java.net.URI;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Sort;
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
