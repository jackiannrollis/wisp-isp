package com.corebalt.isp.identity.application;

import com.corebalt.isp.identity.domain.Credentials;
import com.corebalt.isp.shared.audit.AuditEntry;
import com.corebalt.isp.shared.audit.AuditTrail;
import com.corebalt.isp.shared.security.Permissions;
import com.corebalt.isp.shared.security.Tokens;
import com.corebalt.isp.shared.tenant.TenantContext;
import com.corebalt.isp.shared.web.InvalidRequestException;
import com.corebalt.isp.tenancy.api.SecretStore;
import com.corebalt.isp.tenancy.api.TenantRegistry;
import com.corebalt.isp.tenancy.api.TenantRegistry.TenantRef;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Staff sign-in (docs/08 Security, A2, ADR 0010): email and password (Argon2id), then, in order,
 * a forced password change (invitations and resets), TOTP enrolment when the role requires it and
 * none is set up, and the TOTP code when one is. Five wrong attempts lock the account for 15
 * minutes. Every refusal says the same thing, so nobody learns which part was wrong.
 */
@Service
public class StaffSignIn {

    public static final int MAX_FAILURES = 5;
    public static final Duration LOCKOUT = Duration.ofMinutes(15);
    static final String WRONG = "Wrong organisation, email or password.";

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final TenantRegistry tenants;
    private final SecretStore secrets;
    private final TokenIssuer tokens;
    private final RefreshTokens refreshTokens;
    private final TokenVersions versions;
    private final AuditTrail audit;
    private final Clock clock;

    StaffSignIn(
            final JdbcClient jdbc,
            final TransactionTemplate tx,
            final TenantRegistry tenants,
            final SecretStore secrets,
            final TokenIssuer tokens,
            final RefreshTokens refreshTokens,
            final TokenVersions versions,
            final AuditTrail audit,
            final Clock clock) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.tenants = tenants;
        this.secrets = secrets;
        this.tokens = tokens;
        this.refreshTokens = refreshTokens;
        this.versions = versions;
        this.audit = audit;
        this.clock = clock;
    }

    record Staff(UUID id, UUID tenantId, String email, String passwordHash, String status, int tokenVersion,
                 int failedLogins, Instant lockedUntil, boolean mustChangePassword, Instant passwordExpiresAt,
                 boolean totpEnabled, byte[] totpSecret, String roleName, List<String> permissions, boolean requires2fa) {}

    public SignIn login(final String tenantKey, final String email, final String password) {
        final TenantRef tenant = tenants.findByCodeOrSubdomain(tenantKey).filter(TenantRef::canSignIn)
                .orElseThrow(() -> new AuthException(WRONG));
        return TenantContext.callAsTenant(tenant.id(), () -> {
            final Staff staff = load(email).orElseThrow(() -> new AuthException(WRONG));
            final Instant now = clock.instant();
            if (staff.lockedUntil() != null && now.isBefore(staff.lockedUntil())) {
                throw new AuthException("Too many attempts. Try again in 15 minutes.", true);
            }
            final boolean expiredInvitation = staff.passwordExpiresAt() != null && !now.isBefore(staff.passwordExpiresAt());
            if ("DISABLED".equals(staff.status()) || expiredInvitation || !Credentials.matches(password, staff.passwordHash())) {
                fail(staff, now);
                throw new AuthException(WRONG);
            }
            return afterPassword(staff);
        });
    }

    public SignIn changePassword(final String challenge, final String newPassword) {
        final TokenIssuer.Challenge step = tokens.verifyChallenge(challenge, "CHANGE_PASSWORD");
        return TenantContext.callAsTenant(step.tenantId(), () -> {
            final Staff staff = loadById(step.subjectId()).orElseThrow(() -> new AuthException(WRONG));
            if (!Credentials.acceptablePassword(newPassword, staff.email()) || Credentials.matches(newPassword, staff.passwordHash())) {
                throw InvalidRequestException.of("newPassword", "at least 12 characters, not your email and not the current password");
            }
            tx.executeWithoutResult(s -> {
                jdbc.sql("""
                                update identity_staff_user
                                set password_hash = :hash, must_change_password = false, password_expires_at = null,
                                    status = 'ACTIVE', updated_at = :now
                                where id = :id
                                """)
                        .param("hash", Credentials.hash(newPassword))
                        .param("now", Timestamp.from(clock.instant()))
                        .param("id", staff.id())
                        .update();
                versions.bump(Tokens.STAFF, staff.tenantId(), staff.id());
                audit.recordAs(Tokens.STAFF, staff.id(), AuditEntry.of("identity.password_changed", "staff_user", staff.id()));
            });
            return afterPassword(loadById(staff.id()).orElseThrow());
        });
    }

    public SignIn verifyTotp(final String challenge, final String code) {
        final TokenIssuer.Challenge step = tokens.verifyChallenge(challenge, "TOTP");
        return TenantContext.callAsTenant(step.tenantId(), () -> {
            final Staff staff = loadById(step.subjectId()).orElseThrow(() -> new AuthException(WRONG));
            final Instant now = clock.instant();
            if (staff.lockedUntil() != null && now.isBefore(staff.lockedUntil())) {
                throw new AuthException("Too many attempts. Try again in 15 minutes.", true);
            }
            if (!staff.totpEnabled() || !"ACTIVE".equals(staff.status())) {
                throw new AuthException("This sign-in step has expired; start again.");
            }
            final String secret = tx.execute(s -> secrets.decrypt("staff-totp", staff.totpSecret()));
            if (!Credentials.verifyTotp(secret, code, now)) {
                fail(staff, now);
                throw new AuthException("Wrong code.");
            }
            return signedIn(staff);
        });
    }

    public SignIn enrollTotp(final String challenge, final String code) {
        final TokenIssuer.Challenge step = tokens.verifyChallenge(challenge, "TOTP_ENROLL");
        return TenantContext.callAsTenant(step.tenantId(), () -> {
            final Staff staff = loadById(step.subjectId()).orElseThrow(() -> new AuthException(WRONG));
            if (staff.totpSecret() == null || staff.totpEnabled()) {
                throw new AuthException("This sign-in step has expired; start again.");
            }
            final String secret = tx.execute(s -> secrets.decrypt("staff-totp", staff.totpSecret()));
            if (!Credentials.verifyTotp(secret, code, clock.instant())) {
                throw new AuthException("Wrong code. Check the time on your phone and try again.");
            }
            tx.executeWithoutResult(s -> {
                jdbc.sql("update identity_staff_user set totp_enabled = true, updated_at = now() where id = :id")
                        .param("id", staff.id()).update();
                audit.recordAs(Tokens.STAFF, staff.id(), AuditEntry.of("identity.totp_enrolled", "staff_user", staff.id()));
            });
            return signedIn(loadById(staff.id()).orElseThrow());
        });
    }

    /** The signed-in staff member; {@code impersonatedBy} is the platform admin during an impersonation. */
    public record Me(UUID id, String email, String displayName, UUID tenantId, String tenantCode, String tenantName,
                     String role, List<String> permissions, boolean totpEnabled, UUID impersonatedBy) {}

    public Me me(final UUID staffId, final UUID impersonatedBy) {
        final Staff staff = loadById(staffId).orElseThrow(() -> new AuthException(WRONG));
        final TenantRef tenant = tenants.find(staff.tenantId()).orElseThrow();
        return new Me(staff.id(), staff.email(), tx.execute(s -> jdbc.sql("select display_name from identity_staff_user where id = :id")
                .param("id", staffId).query(String.class).single()), tenant.id(), tenant.code(), tenant.name(), staff.roleName(),
                impersonatedBy == null ? staff.permissions() : Permissions.allowedWhileImpersonating(staff.permissions()),
                staff.totpEnabled(), impersonatedBy);
    }

    /** Signed in: the current password proves it is them; every session ends, including this one. */
    public void changeOwnPassword(final UUID staffId, final String current, final String newPassword) {
        final Staff staff = loadById(staffId).orElseThrow(() -> new AuthException(WRONG));
        if (!Credentials.matches(current, staff.passwordHash())) {
            fail(staff, clock.instant());
            throw new AuthException("The current password is wrong.");
        }
        if (!Credentials.acceptablePassword(newPassword, staff.email()) || Credentials.matches(newPassword, staff.passwordHash())) {
            throw InvalidRequestException.of("newPassword", "at least 12 characters, not your email and not the current password");
        }
        tx.executeWithoutResult(s -> {
            jdbc.sql("update identity_staff_user set password_hash = :hash, updated_at = now() where id = :id")
                    .param("hash", Credentials.hash(newPassword)).param("id", staffId).update();
            versions.bump(Tokens.STAFF, staff.tenantId(), staffId);
            refreshTokens.revokeSubject(Tokens.STAFF, staffId);
            audit.record(AuditEntry.of("identity.password_changed", "staff_user", staffId));
        });
    }

    /** For the refresh endpoint: a fresh access token if the staff member may still sign in. */
    Optional<String> reissue(final UUID tenantId, final UUID staffId) {
        return TenantContext.callAsTenant(tenantId, () -> loadById(staffId)
                .filter(s -> "ACTIVE".equals(s.status()) && !s.mustChangePassword())
                .map(s -> tokens.access(subject(s))));
    }

    private SignIn afterPassword(final Staff staff) {
        if (staff.mustChangePassword()) {
            return SignIn.next("CHANGE_PASSWORD", tokens.challenge(Tokens.STAFF, staff.id(), staff.tenantId(), "CHANGE_PASSWORD"));
        }
        if (staff.totpEnabled()) {
            return SignIn.next("TOTP_REQUIRED", tokens.challenge(Tokens.STAFF, staff.id(), staff.tenantId(), "TOTP"));
        }
        if (staff.requires2fa()) {
            final String secret = Credentials.newTotpSecret();
            tx.executeWithoutResult(s -> {
                final byte[] blob = secrets.encrypt("staff-totp", secret);
                jdbc.sql("update identity_staff_user set totp_secret_enc = :blob, totp_key_version = :version, updated_at = now() where id = :id")
                        .param("blob", blob).param("version", java.nio.ByteBuffer.wrap(blob, 1, 4).getInt()).param("id", staff.id()).update();
            });
            final String issuer = "Corebalt " + tenants.find(staff.tenantId()).map(TenantRef::code).orElse("");
            return SignIn.enroll(tokens.challenge(Tokens.STAFF, staff.id(), staff.tenantId(), "TOTP_ENROLL"), secret,
                    Credentials.otpauthUri(issuer, staff.email(), secret), null);
        }
        return signedIn(staff);
    }

    private SignIn signedIn(final Staff staff) {
        tx.executeWithoutResult(s -> {
            jdbc.sql("update identity_staff_user set failed_logins = 0, locked_until = null, last_login_at = :now where id = :id")
                    .param("now", Timestamp.from(clock.instant())).param("id", staff.id()).update();
            audit.recordAs(Tokens.STAFF, staff.id(), AuditEntry.of("identity.signed_in", "staff_user", staff.id()));
        });
        return SignIn.signedIn(tokens.access(subject(staff)), refreshTokens.issue(Tokens.STAFF, staff.id(), staff.tenantId()));
    }

    static TokenIssuer.Subject subject(final Staff staff) {
        return new TokenIssuer.Subject(Tokens.STAFF, staff.id(), staff.tenantId(), staff.roleName(), staff.permissions(),
                staff.tokenVersion());
    }

    private void fail(final Staff staff, final Instant now) {
        tx.executeWithoutResult(s -> {
            final int failures = staff.failedLogins() + 1;
            jdbc.sql("update identity_staff_user set failed_logins = :failures, locked_until = :locked where id = :id")
                    .param("failures", failures >= MAX_FAILURES ? 0 : failures)
                    .param("locked", failures >= MAX_FAILURES ? Timestamp.from(now.plus(LOCKOUT)) : null)
                    .param("id", staff.id())
                    .update();
            if (failures >= MAX_FAILURES) {
                audit.recordAs(Tokens.STAFF, staff.id(), new AuditEntry("identity.locked_out", "staff_user", staff.id(), null,
                        Map.of("minutes", LOCKOUT.toMinutes()), null));
            }
        });
    }

    Optional<Staff> load(final String email) {
        return query("lower(s.email) = lower(:key)", email == null ? "" : email.strip());
    }

    Optional<Staff> loadById(final UUID id) {
        return query("s.id = cast(:key as uuid)", id.toString());
    }

    private Optional<Staff> query(final String where, final String key) {
        return tx.execute(s -> jdbc.sql("""
                        select s.id, s.tenant_id, s.email, s.password_hash, s.status, s.token_version, s.failed_logins,
                               s.locked_until, s.must_change_password, s.password_expires_at, s.totp_enabled, s.totp_secret_enc,
                               r.name, r.permissions, coalesce(r.requires_2fa, false), coalesce(r.built_in, false)
                        from identity_staff_user s left join identity_role r on r.id = s.role_id
                        where """ + " " + where)
                .param("key", key)
                .query((rs, n) -> new Staff(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getInt(6), rs.getInt(7),
                        rs.getTimestamp(8) == null ? null : rs.getTimestamp(8).toInstant(), rs.getBoolean(9),
                        rs.getTimestamp(10) == null ? null : rs.getTimestamp(10).toInstant(), rs.getBoolean(11), rs.getBytes(12),
                        rs.getString(13),
                        permissions(rs.getString(13), rs.getBoolean(16), rs.getArray(14)),
                        rs.getBoolean(15)))
                .optional());
    }

    /**
     * The built-in Owner role holds the whole catalogue (A2), so a code a release adds reaches an
     * existing tenant's Owner at their next sign-in without touching the stored role row.
     */
    private static List<String> permissions(final String roleName, final boolean builtIn, final java.sql.Array stored)
            throws java.sql.SQLException {
        if (builtIn && Roles.OWNER.equalsIgnoreCase(roleName)) {
            return Permissions.ALL;
        }
        return stored == null ? List.of() : Arrays.asList((String[]) stored.getArray());
    }
}
