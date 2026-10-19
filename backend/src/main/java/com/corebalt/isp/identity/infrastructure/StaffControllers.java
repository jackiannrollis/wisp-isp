package com.corebalt.isp.identity.infrastructure;

import com.corebalt.isp.identity.application.AuditLog;
import com.corebalt.isp.identity.application.Roles;
import com.corebalt.isp.identity.application.StaffAdmin;
import com.corebalt.isp.identity.application.TemporaryPasswordSms;
import com.corebalt.isp.identity.application.StaffSignIn;
import com.corebalt.isp.shared.security.CurrentActor;
import com.corebalt.isp.shared.security.Permissions;
import com.corebalt.isp.shared.web.PageRequests;
import com.corebalt.isp.shared.web.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Staff, roles, permissions, the audit log and the signed-in staff member (docs/08, A1, A2). */
final class StaffControllers {

    private StaffControllers() {}

    record RoleChange(@NotNull UUID roleId) {}

    record Reason(String reason) {}

    record OwnPassword(@NotBlank String currentPassword, @NotBlank String newPassword) {}

    record PermissionDto(String code, boolean requires2fa) {}

    @RestController
    @RequestMapping("/api/v1/staff")
    @Tag(name = "Staff")
    static class StaffApi {

        private static final Set<String> SORTABLE = Set.of("email", "displayName", "createdAt", "lastLoginAt");

        private final StaffAdmin staff;
        private final TemporaryPasswordSms sms;

        StaffApi(final StaffAdmin staff, final TemporaryPasswordSms sms) {
            this.staff = staff;
            this.sms = sms;
        }

        @GetMapping
        @PreAuthorize("hasAuthority('staff.manage')")
        @Operation(summary = "List staff; q matches email or name")
        PageResponse<StaffAdmin.StaffDto> list(
                @RequestParam(required = false) final String status,
                @RequestParam(required = false) final String q,
                @RequestParam(defaultValue = "0") final int page,
                @RequestParam(defaultValue = "50") final int size,
                @RequestParam(required = false) final String sort) {
            return staff.list(status, q == null || q.isBlank() ? null : q,
                    PageRequests.of(page, size, sort, SORTABLE, Sort.by("createdAt")));
        }

        @GetMapping("/{id}")
        @PreAuthorize("hasAuthority('staff.manage')")
        StaffAdmin.StaffDto get(@PathVariable final UUID id) {
            return staff.get(id);
        }

        @PostMapping
        @PreAuthorize("hasAuthority('staff.manage')")
        @Operation(summary = "Invite a staff member; the temporary password (valid 72 h) is in this response only, "
                + "and also sent to their phone by SMS when sendSms is true")
        ResponseEntity<StaffAdmin.Invitation> invite(@RequestBody final StaffAdmin.InviteRequest request,
                                                     @RequestParam(defaultValue = "false") final boolean sendSms) {
            if (sendSms) {
                TemporaryPasswordSms.requirePhone(request.phone());
            }
            final StaffAdmin.Invitation created = staff.invite(request);
            final StaffAdmin.Invitation invitation = sendSms ? sms.send(created) : created;
            return ResponseEntity.created(URI.create("/api/v1/staff/" + invitation.staff().id())).body(invitation);
        }

        @PostMapping("/{id}/temporary-password")
        @PreAuthorize("hasAuthority('staff.manage')")
        @Operation(summary = "Regenerate an unused invitation, or reset a password: a new temporary password, shown once; sessions end")
        StaffAdmin.Invitation temporaryPassword(@PathVariable final UUID id,
                                                @RequestParam(defaultValue = "false") final boolean sendSms) {
            if (sendSms) {
                TemporaryPasswordSms.requirePhone(staff.get(id).phone());
            }
            final StaffAdmin.Invitation invitation = staff.newTemporaryPassword(id);
            return sendSms ? sms.send(invitation) : invitation;
        }

        @PostMapping("/{id}/disable")
        @PreAuthorize("hasAuthority('staff.manage')")
        @Operation(summary = "Disable: every session ends at the next request")
        StaffAdmin.StaffDto disable(@PathVariable final UUID id, @RequestBody(required = false) final Reason reason) {
            return staff.disable(id, reason == null ? null : reason.reason());
        }

        @PostMapping("/{id}/enable")
        @PreAuthorize("hasAuthority('staff.manage')")
        StaffAdmin.StaffDto enable(@PathVariable final UUID id) {
            return staff.enable(id);
        }

        @PutMapping("/{id}/role")
        @PreAuthorize("hasAuthority('staff.manage')")
        @Operation(summary = "Change the role: every session ends at the next request")
        StaffAdmin.StaffDto role(@PathVariable final UUID id, @Valid @RequestBody final RoleChange request) {
            return staff.changeRole(id, request.roleId());
        }
    }

    @RestController
    @Tag(name = "Staff")
    static class RolesApi {

        private final Roles roles;

        RolesApi(final Roles roles) {
            this.roles = roles;
        }

        @GetMapping("/api/v1/permissions")
        @Operation(summary = "The fixed permission codes; those marked requires2fa make a role require 2FA (A2)")
        List<PermissionDto> permissions() {
            return Permissions.ALL.stream().map(code -> new PermissionDto(code, Roles.NEEDS_2FA.contains(code))).toList();
        }

        @GetMapping("/api/v1/roles")
        List<Roles.RoleDto> list() {
            return roles.list();
        }

        @PostMapping("/api/v1/roles")
        @PreAuthorize("hasAuthority('staff.manage')")
        ResponseEntity<Roles.RoleDto> create(@RequestBody final Roles.RoleRequest request) {
            final Roles.RoleDto role = roles.create(request);
            return ResponseEntity.created(URI.create("/api/v1/roles/" + role.id())).body(role);
        }

        @PutMapping("/api/v1/roles/{id}")
        @PreAuthorize("hasAuthority('staff.manage')")
        @Operation(summary = "Edit a role; everyone holding it gets a new token on the next request")
        Roles.RoleDto update(@PathVariable final UUID id, @RequestBody final Roles.RoleRequest request) {
            return roles.update(id, request);
        }
    }

    @RestController
    @Tag(name = "Audit")
    static class AuditApi {

        private final AuditLog audit;

        AuditApi(final AuditLog audit) {
            this.audit = audit;
        }

        @GetMapping("/api/v1/audit-log")
        @PreAuthorize("hasAuthority('audit.read')")
        @Operation(summary = "The tenant's audit log, newest first; action matches by prefix (e.g. identity.)")
        PageResponse<AuditLog.AuditRow> list(
                @RequestParam(required = false) final String action,
                @RequestParam(required = false) final String targetType,
                @RequestParam(required = false) final UUID targetId,
                @RequestParam(required = false) final UUID actorId,
                @RequestParam(required = false) final Instant from,
                @RequestParam(required = false) final Instant to,
                @RequestParam(defaultValue = "0") final int page,
                @RequestParam(defaultValue = "50") final int size,
                @RequestParam(required = false) final String sort) {
            return audit.list(new AuditLog.Filter(blank(action), blank(targetType), targetId, actorId, from, to),
                    PageRequests.of(page, size, sort, Set.of("occurredAt"), Sort.by("occurredAt").descending()));
        }

        private static String blank(final String value) {
            return value == null || value.isBlank() ? null : value.strip();
        }
    }

    @RestController
    @RequestMapping("/api/v1/me")
    @Tag(name = "Staff")
    static class MeApi {

        private final StaffSignIn signIn;

        MeApi(final StaffSignIn signIn) {
            this.signIn = signIn;
        }

        @GetMapping
        @Operation(summary = "The signed-in staff member, role and permissions; impersonatedBy is set during an impersonation")
        StaffSignIn.Me me() {
            return signIn.me(CurrentActor.staffId().orElseThrow(), CurrentActor.actingPlatformAdmin().orElse(null));
        }

        @PostMapping("/password")
        @Operation(summary = "Change your password: every session ends, so sign in again")
        ResponseEntity<Void> password(@Valid @RequestBody final OwnPassword request) {
            signIn.changeOwnPassword(CurrentActor.staffId().orElseThrow(), request.currentPassword(), request.newPassword());
            return ResponseEntity.noContent().build();
        }
    }
}
