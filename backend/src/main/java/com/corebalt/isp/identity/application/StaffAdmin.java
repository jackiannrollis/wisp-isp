package com.corebalt.isp.identity.application;

import com.corebalt.isp.customers.api.CustomerFacade;
import com.corebalt.isp.identity.domain.Credentials;
import com.corebalt.isp.shared.audit.AuditEntry;
import com.corebalt.isp.shared.audit.AuditTrail;
import com.corebalt.isp.shared.id.UuidV7;
import com.corebalt.isp.shared.security.CurrentActor;
import com.corebalt.isp.shared.security.Permissions;
import com.corebalt.isp.shared.security.Tokens;
import com.corebalt.isp.shared.tenant.TenantContext;
import com.corebalt.isp.shared.web.ConflictException;
import com.corebalt.isp.shared.web.InvalidRequestException;
import com.corebalt.isp.shared.web.NotFoundException;
import com.corebalt.isp.shared.web.PageResponse;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Staff accounts of a tenant (docs/08). An invitation is a generated temporary password (14
 * characters, shown once, valid 72 hours); the first sign-in forces a new password and, when the
 * role requires it, TOTP enrolment. An unused invitation cannot be read again, only regenerated.
 * Disabling, a new temporary password and a role change bump the token version and end every
 * session. Nobody can disable or re-role themselves, and a tenant always keeps one active staff
 * member who can manage staff.
 */
@Service
public class StaffAdmin {

    public static final Duration INVITATION_TTL = Duration.ofHours(72);
    static final int TEMPORARY_LENGTH = 14;
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    public record StaffDto(UUID id, String email, String displayName, String phone, String status, UUID roleId,
                           String roleName, boolean totpEnabled, boolean mustChangePassword, Instant invitationExpiresAt,
                           Instant lastLoginAt, Instant createdAt) {}

    public record InviteRequest(String email, String displayName, String phone, UUID roleId) {}

    /**
     * The temporary password is in this response only. {@code smsDelivery} is set when it was also sent by
     * SMS (Q11): SENT, NOT_CONFIGURED or FAILED.
     */
    public record Invitation(StaffDto staff, String temporaryPassword, Instant expiresAt, String smsDelivery) {

        Invitation(final StaffDto staff, final String temporaryPassword, final Instant expiresAt) {
            this(staff, temporaryPassword, expiresAt, null);
        }

        Invitation withSmsDelivery(final String delivery) {
            return new Invitation(staff, temporaryPassword, expiresAt, delivery);
        }
    }

    private final JdbcClient jdbc;
    private final Roles roles;
    private final TokenVersions versions;
    private final RefreshTokens refreshTokens;
    private final AuditTrail audit;
    private final CustomerFacade phones;
    private final Clock clock;

    StaffAdmin(final JdbcClient jdbc, final Roles roles, final TokenVersions versions, final RefreshTokens refreshTokens,
               final AuditTrail audit, final CustomerFacade phones, final Clock clock) {
        this.jdbc = jdbc;
        this.roles = roles;
        this.versions = versions;
        this.refreshTokens = refreshTokens;
        this.audit = audit;
        this.phones = phones;
        this.clock = clock;
    }

    @Transactional
    public PageResponse<StaffDto> list(final String status, final String q, final Pageable page) {
        final String where = " where (cast(:status as text) is null or s.status = :status)"
                + " and (cast(:q as text) is null or s.email ilike :like or s.display_name ilike :like)";
        final long total = jdbc.sql("select count(*) from identity_staff_user s" + where)
                .param("status", status).param("q", q).param("like", like(q)).query(Long.class).single();
        final String order = page.getSort().stream()
                .map(o -> switch (o.getProperty()) {
                    case "email" -> "s.email";
                    case "displayName" -> "s.display_name";
                    case "lastLoginAt" -> "s.last_login_at";
                    default -> "s.created_at";
                } + (o.isDescending() ? " desc nulls last" : " asc"))
                .reduce((a, b) -> a + ", " + b).orElse("s.created_at");
        final List<StaffDto> items = jdbc.sql(SELECT + where + " order by " + order + ", s.id limit :limit offset :offset")
                .param("status", status).param("q", q).param("like", like(q))
                .param("limit", page.getPageSize()).param("offset", page.getOffset())
                .query((rs, n) -> map(rs)).list();
        return new PageResponse<>(items, page.getPageNumber(), page.getPageSize(), total);
    }

    @Transactional
    public StaffDto get(final UUID id) {
        return jdbc.sql(SELECT + " where s.id = :id").param("id", id).query((rs, n) -> map(rs)).optional()
                .orElseThrow(() -> new NotFoundException("staff member"));
    }

    /** Email and name of an invitation; {@code prefix} names the fields in the error (e.g. owner). */
    static void validate(final InviteRequest request, final String prefix) {
        if (request.email() == null || !EMAIL.matcher(request.email().strip()).matches()) {
            throw InvalidRequestException.of(prefix == null ? "email" : prefix + "Email", "a valid email address");
        }
        if (request.displayName() == null || request.displayName().isBlank()) {
            throw InvalidRequestException.of(prefix == null ? "displayName" : prefix + "Name", "required");
        }
    }

    /** A staff phone is stored in E.164, like customers' (Q11: it may receive the temporary password). */
    private String phone(final String phone) {
        if (phone == null || phone.isBlank()) {
            return null;
        }
        return phones.normalisePhone(phone.strip())
                .orElseThrow(() -> InvalidRequestException.of("phone", "not a valid phone number"));
    }

    @Transactional
    public Invitation invite(final InviteRequest request) {
        validate(request, null);
        roles.ensureBuiltIns();
        roles.get(requireRole(request.roleId()));
        if (jdbc.sql("select count(*) from identity_staff_user where lower(email) = lower(:email)")
                .param("email", request.email().strip()).query(Long.class).single() > 0) {
            throw new ConflictException("A staff member with this email already exists.");
        }
        final UUID id = UuidV7.next(clock);
        final Instant now = clock.instant();
        final String temporary = Credentials.readable(TEMPORARY_LENGTH);
        jdbc.sql("""
                        insert into identity_staff_user
                            (id, tenant_id, email, display_name, phone, password_hash, status, role_id, must_change_password,
                             password_expires_at, created_at, updated_at)
                        values (:id, :tenantId, :email, :name, :phone, :hash, 'INVITED', :role, true, :expires, :now, :now)
                        """)
                .param("id", id)
                .param("tenantId", TenantContext.requireTenantId())
                .param("email", request.email().strip())
                .param("name", request.displayName().strip())
                .param("phone", phone(request.phone()))
                .param("hash", Credentials.hash(temporary))
                .param("role", request.roleId())
                .param("expires", Timestamp.from(now.plus(INVITATION_TTL)))
                .param("now", Timestamp.from(now))
                .update();
        audit.record(AuditEntry.of("identity.staff_invited", "staff_user", id,
                Map.of("email", request.email().strip(), "roleId", request.roleId().toString())));
        return new Invitation(get(id), temporary, now.plus(INVITATION_TTL));
    }

    /** A new temporary password: for an unused invitation, or to reset an active member's password. */
    @Transactional
    public Invitation newTemporaryPassword(final UUID id) {
        final StaffDto staff = get(id);
        if ("DISABLED".equals(staff.status())) {
            throw new ConflictException("Enable this staff member first.");
        }
        notSelf(id, "reset your own password here; use Change password");
        final Invitation invitation = issueTemporary(id);
        audit.record(AuditEntry.of("INVITED".equals(staff.status()) ? "identity.invitation_regenerated"
                : "identity.password_reset", "staff_user", id));
        return invitation;
    }

    /**
     * The platform console's version, for an Owner whose invitation was lost before the first sign-in
     * (docs/07 onboarding step 1): only an invitation never used can be reissued from outside the
     * tenant; an active member is reset by the tenant's own staff. The caller audits in both logs.
     */
    @Transactional
    public Invitation reissueInvitation(final UUID id) {
        final StaffDto staff = get(id);
        if (!"INVITED".equals(staff.status())) {
            throw new ConflictException("Only an invitation that was never used can be reissued from the platform; "
                    + "an active member's password is reset by the tenant's own staff.");
        }
        return issueTemporary(id);
    }

    private Invitation issueTemporary(final UUID id) {
        final Instant now = clock.instant();
        final String temporary = Credentials.readable(TEMPORARY_LENGTH);
        jdbc.sql("""
                        update identity_staff_user set password_hash = :hash, must_change_password = true,
                            password_expires_at = :expires, failed_logins = 0, locked_until = null, updated_at = :now
                        where id = :id
                        """)
                .param("hash", Credentials.hash(temporary))
                .param("expires", Timestamp.from(now.plus(INVITATION_TTL)))
                .param("now", Timestamp.from(now))
                .param("id", id)
                .update();
        endSessions(id);
        return new Invitation(get(id), temporary, now.plus(INVITATION_TTL));
    }

    @Transactional
    public StaffDto disable(final UUID id, final String reason) {
        final StaffDto staff = get(id);
        notSelf(id, "disable yourself");
        if (!"DISABLED".equals(staff.status())) {
            final boolean wasManager = isManager(id);
            jdbc.sql("update identity_staff_user set status = 'DISABLED', updated_at = now() where id = :id").param("id", id).update();
            keepAManager(wasManager);
            endSessions(id);
            audit.record(new AuditEntry("identity.staff_disabled", "staff_user", id, Map.of("status", staff.status()),
                    Map.of("status", "DISABLED"), reason));
        }
        return get(id);
    }

    @Transactional
    public StaffDto enable(final UUID id) {
        final StaffDto staff = get(id);
        if ("DISABLED".equals(staff.status())) {
            final String status = staff.mustChangePassword() ? "INVITED" : "ACTIVE";
            jdbc.sql("update identity_staff_user set status = :status, failed_logins = 0, locked_until = null, updated_at = now() where id = :id")
                    .param("status", status).param("id", id).update();
            audit.record(new AuditEntry("identity.staff_enabled", "staff_user", id, Map.of("status", "DISABLED"),
                    Map.of("status", status), null));
        }
        return get(id);
    }

    @Transactional
    public StaffDto changeRole(final UUID id, final UUID roleId) {
        final StaffDto staff = get(id);
        notSelf(id, "change your own role");
        final Roles.RoleDto role = roles.get(requireRole(roleId));
        if (!roleId.equals(staff.roleId())) {
            final boolean wasManager = isManager(id);
            jdbc.sql("update identity_staff_user set role_id = :role, updated_at = now() where id = :id")
                    .param("role", roleId).param("id", id).update();
            keepAManager(wasManager);
            endSessions(id);
            audit.record(new AuditEntry("identity.staff_role_changed", "staff_user", id,
                    Map.of("role", String.valueOf(staff.roleName())), Map.of("role", role.name()), null));
        }
        return get(id);
    }

    /** Ends every session: access tokens fail on the next request, refresh tokens are revoked. */
    private void endSessions(final UUID id) {
        versions.bump(Tokens.STAFF, TenantContext.requireTenantId(), id);
        refreshTokens.revokeSubject(Tokens.STAFF, id);
    }

    private boolean isManager(final UUID id) {
        return jdbc.sql("""
                        select count(*) from identity_staff_user s join identity_role r on r.id = s.role_id
                        where s.id = :id and s.status <> 'DISABLED' and :code = any (r.permissions)
                        """)
                .param("id", id).param("code", Permissions.STAFF_MANAGE).query(Long.class).single() > 0;
    }

    /** Refuses a change that removes the tenant's last staff member able to manage staff. */
    private void keepAManager(final boolean targetWasManager) {
        if (!targetWasManager) {
            return;
        }
        final long managers = jdbc.sql("""
                        select count(*) from identity_staff_user s join identity_role r on r.id = s.role_id
                        where s.status <> 'DISABLED' and :code = any (r.permissions)
                        """)
                .param("code", Permissions.STAFF_MANAGE).query(Long.class).single();
        if (managers == 0) {
            throw new ConflictException("At least one active staff member must be able to manage staff.");
        }
    }

    private static void notSelf(final UUID id, final String what) {
        if (CurrentActor.actingPlatformAdmin().isEmpty() && CurrentActor.staffId().map(id::equals).orElse(false)) {
            throw new ConflictException("You cannot " + what + ".");
        }
    }

    private static UUID requireRole(final UUID roleId) {
        if (roleId == null) {
            throw InvalidRequestException.of("roleId", "required");
        }
        return roleId;
    }

    private static String like(final String q) {
        return q == null ? null : "%" + q.strip().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    private static final String SELECT = """
            select s.id, s.email, s.display_name, s.phone, s.status, s.role_id, r.name, s.totp_enabled, s.must_change_password,
                   s.password_expires_at, s.last_login_at, s.created_at
            from identity_staff_user s left join identity_role r on r.id = s.role_id""";

    private static StaffDto map(final java.sql.ResultSet rs) throws java.sql.SQLException {
        return new StaffDto(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                rs.getObject(6, UUID.class), rs.getString(7), rs.getBoolean(8), rs.getBoolean(9),
                rs.getTimestamp(10) == null ? null : rs.getTimestamp(10).toInstant(),
                rs.getTimestamp(11) == null ? null : rs.getTimestamp(11).toInstant(), rs.getTimestamp(12).toInstant());
    }
}
