package com.corebalt.isp.identity.application;

import com.corebalt.isp.identity.domain.Credentials;
import com.corebalt.isp.shared.id.UuidV7;
import com.corebalt.isp.shared.security.Tokens;
import com.corebalt.isp.shared.tenant.TenantContext;
import com.corebalt.isp.shared.web.InvalidRequestException;
import com.corebalt.isp.tenancy.api.SecretStore;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Platform admin sign-in (ADR 0010): password, a forced change after bootstrap or a break-glass
 * reset, then TOTP, which is mandatory: an admin without it enrols before getting any token and
 * receives {@link #RECOVERY_CODES} recovery codes, shown once and stored hashed, each usable once in
 * place of a TOTP code. Same lockout as staff. Every step is in the platform audit log.
 */
@Service
public class PlatformSignIn {

    public static final int RECOVERY_CODES = 10;
    private static final Pattern RECOVERY = Pattern.compile("[A-Z0-9]{4}-[A-Z0-9]{4}");
    static final String WRONG = "Wrong email or password.";
    static final String PURPOSE = "platform-totp";

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final SecretStore secrets;
    private final TokenIssuer tokens;
    private final RefreshTokens refreshTokens;
    private final TokenVersions versions;
    private final PlatformAudit audit;
    private final Clock clock;

    PlatformSignIn(
            final JdbcClient jdbc,
            final TransactionTemplate tx,
            final SecretStore secrets,
            final TokenIssuer tokens,
            final RefreshTokens refreshTokens,
            final TokenVersions versions,
            final PlatformAudit audit,
            final Clock clock) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.secrets = secrets;
        this.tokens = tokens;
        this.refreshTokens = refreshTokens;
        this.versions = versions;
        this.audit = audit;
        this.clock = clock;
    }

    record Admin(UUID id, String email, String passwordHash, String status, int tokenVersion, int failedLogins,
                 Instant lockedUntil, boolean mustChangePassword, boolean totpEnabled, byte[] totpSecret) {}

    public SignIn login(final String email, final String password) {
        return TenantContext.callAsPlatform("platform-sign-in", () -> {
            final Admin admin = byEmail(email).orElseThrow(() -> new AuthException(WRONG));
            final Instant now = clock.instant();
            if (admin.lockedUntil() != null && now.isBefore(admin.lockedUntil())) {
                throw new AuthException("Too many attempts. Try again in 15 minutes.", true);
            }
            if (!"ACTIVE".equals(admin.status()) || !Credentials.matches(password, admin.passwordHash())) {
                fail(admin, now);
                throw new AuthException(WRONG);
            }
            return afterPassword(admin);
        });
    }

    public SignIn changePassword(final String challenge, final String newPassword) {
        final TokenIssuer.Challenge step = platformChallenge(challenge, "CHANGE_PASSWORD");
        return TenantContext.callAsPlatform("platform-sign-in", () -> {
            final Admin admin = byId(step.subjectId()).orElseThrow(() -> new AuthException(WRONG));
            if (!Credentials.acceptablePassword(newPassword, admin.email()) || Credentials.matches(newPassword, admin.passwordHash())) {
                throw InvalidRequestException.of("newPassword", "at least 12 characters, not your email and not the current password");
            }
            tx.executeWithoutResult(s -> {
                jdbc.sql("""
                                update identity_platform_admin set password_hash = :hash, must_change_password = false,
                                    updated_at = :now where id = :id
                                """)
                        .param("hash", Credentials.hash(newPassword))
                        .param("now", Timestamp.from(clock.instant()))
                        .param("id", admin.id())
                        .update();
                versions.bump(Tokens.PLATFORM, null, admin.id());
                audit.record(admin.id(), "platform.password_changed", "platform_admin", admin.id(), null);
            });
            return afterPassword(byId(admin.id()).orElseThrow());
        });
    }

    /** A TOTP code, or one of the recovery codes (used up). */
    public SignIn verifyTotp(final String challenge, final String code) {
        final TokenIssuer.Challenge step = platformChallenge(challenge, "TOTP");
        return TenantContext.callAsPlatform("platform-sign-in", () -> {
            final Admin admin = byId(step.subjectId()).orElseThrow(() -> new AuthException(WRONG));
            final Instant now = clock.instant();
            if (admin.lockedUntil() != null && now.isBefore(admin.lockedUntil())) {
                throw new AuthException("Too many attempts. Try again in 15 minutes.", true);
            }
            if (!admin.totpEnabled() || !"ACTIVE".equals(admin.status())) {
                throw new AuthException("This sign-in step has expired; start again.");
            }
            final String normalized = code == null ? "" : code.strip().toUpperCase(java.util.Locale.ROOT);
            final boolean ok = RECOVERY.matcher(normalized).matches()
                    ? useRecoveryCode(admin.id(), normalized)
                    : Credentials.verifyTotp(tx.execute(s -> secrets.decryptForPlatform(PURPOSE, admin.totpSecret())), normalized, now);
            if (!ok) {
                fail(admin, now);
                throw new AuthException("Wrong code.");
            }
            return signedIn(admin);
        });
    }

    public SignIn enrollTotp(final String challenge, final String code) {
        final TokenIssuer.Challenge step = platformChallenge(challenge, "TOTP_ENROLL");
        return TenantContext.callAsPlatform("platform-sign-in", () -> {
            final Admin admin = byId(step.subjectId()).orElseThrow(() -> new AuthException(WRONG));
            if (admin.totpSecret() == null || admin.totpEnabled()) {
                throw new AuthException("This sign-in step has expired; start again.");
            }
            final String secret = tx.execute(s -> secrets.decryptForPlatform(PURPOSE, admin.totpSecret()));
            if (!Credentials.verifyTotp(secret, code, clock.instant())) {
                throw new AuthException("Wrong code. Check the time on your phone and try again.");
            }
            tx.executeWithoutResult(s -> {
                jdbc.sql("update identity_platform_admin set totp_enabled = true, updated_at = now() where id = :id")
                        .param("id", admin.id()).update();
                audit.record(admin.id(), "platform.totp_enrolled", "platform_admin", admin.id(), null);
            });
            return signedIn(byId(admin.id()).orElseThrow());
        });
    }

    Optional<String> reissue(final UUID adminId) {
        return TenantContext.callAsPlatform("platform-refresh", () -> byId(adminId)
                .filter(a -> "ACTIVE".equals(a.status()) && a.totpEnabled() && !a.mustChangePassword())
                .map(a -> tokens.access(subject(a))));
    }

    private TokenIssuer.Challenge platformChallenge(final String challenge, final String purpose) {
        final TokenIssuer.Challenge step = tokens.verifyChallenge(challenge, purpose);
        if (!Tokens.PLATFORM.equals(step.subjectType())) {
            throw new AuthException("This sign-in step has expired; start again.");
        }
        return step;
    }

    private SignIn afterPassword(final Admin admin) {
        if (admin.mustChangePassword()) {
            return SignIn.next("CHANGE_PASSWORD", tokens.challenge(Tokens.PLATFORM, admin.id(), null, "CHANGE_PASSWORD"));
        }
        if (admin.totpEnabled()) {
            return SignIn.next("TOTP_REQUIRED", tokens.challenge(Tokens.PLATFORM, admin.id(), null, "TOTP"));
        }
        final String secret = Credentials.newTotpSecret();
        final List<String> codes = new ArrayList<>();
        for (int i = 0; i < RECOVERY_CODES; i++) {
            final String raw = Credentials.readable(8).toUpperCase(java.util.Locale.ROOT);
            codes.add(raw.substring(0, 4) + "-" + raw.substring(4));
        }
        tx.executeWithoutResult(s -> {
            jdbc.sql("update identity_platform_admin set totp_secret_enc = :blob, updated_at = now() where id = :id")
                    .param("blob", secrets.encryptForPlatform(PURPOSE, secret)).param("id", admin.id()).update();
            jdbc.sql("update identity_platform_recovery_code set used_at = now() where admin_id = :id and used_at is null")
                    .param("id", admin.id()).update();
            for (final String code : codes) {
                jdbc.sql("insert into identity_platform_recovery_code (id, admin_id, code_hash) values (:id, :admin, :hash)")
                        .param("id", UuidV7.next(clock)).param("admin", admin.id()).param("hash", Credentials.hash(code)).update();
            }
        });
        return SignIn.enroll(tokens.challenge(Tokens.PLATFORM, admin.id(), null, "TOTP_ENROLL"), secret,
                Credentials.otpauthUri("Corebalt platform", admin.email(), secret), codes);
    }

    private boolean useRecoveryCode(final UUID adminId, final String code) {
        return Boolean.TRUE.equals(tx.execute(s -> {
            final List<Map.Entry<UUID, String>> unused = jdbc.sql(
                            "select id, code_hash from identity_platform_recovery_code where admin_id = :id and used_at is null for update")
                    .param("id", adminId)
                    .query((rs, n) -> Map.entry(rs.getObject(1, UUID.class), rs.getString(2)))
                    .list();
            for (final Map.Entry<UUID, String> row : unused) {
                if (Credentials.matches(code, row.getValue())) {
                    jdbc.sql("update identity_platform_recovery_code set used_at = now() where id = :id")
                            .param("id", row.getKey()).update();
                    audit.record(adminId, "platform.recovery_code_used", "platform_admin", adminId,
                            Map.of("remaining", unused.size() - 1));
                    return true;
                }
            }
            return false;
        }));
    }

    private SignIn signedIn(final Admin admin) {
        tx.executeWithoutResult(s -> {
            jdbc.sql("update identity_platform_admin set failed_logins = 0, locked_until = null, last_login_at = :now where id = :id")
                    .param("now", Timestamp.from(clock.instant())).param("id", admin.id()).update();
            audit.record(admin.id(), "platform.signed_in", "platform_admin", admin.id(), null);
        });
        return SignIn.signedIn(tokens.access(subject(admin)), refreshTokens.issue(Tokens.PLATFORM, admin.id(), null));
    }

    private static TokenIssuer.Subject subject(final Admin admin) {
        return new TokenIssuer.Subject(Tokens.PLATFORM, admin.id(), null, "PLATFORM_ADMIN", null, admin.tokenVersion());
    }

    private void fail(final Admin admin, final Instant now) {
        tx.executeWithoutResult(s -> {
            final int failures = admin.failedLogins() + 1;
            final boolean lock = failures >= StaffSignIn.MAX_FAILURES;
            jdbc.sql("update identity_platform_admin set failed_logins = :failures, locked_until = :locked where id = :id")
                    .param("failures", lock ? 0 : failures)
                    .param("locked", lock ? Timestamp.from(now.plus(StaffSignIn.LOCKOUT)) : null)
                    .param("id", admin.id())
                    .update();
            if (lock) {
                audit.record(admin.id(), "platform.locked_out", "platform_admin", admin.id(),
                        Map.of("minutes", StaffSignIn.LOCKOUT.toMinutes()));
            }
        });
    }

    Optional<Admin> byEmail(final String email) {
        return query("lower(email) = lower(:key)", email == null ? "" : email.strip());
    }

    Optional<Admin> byId(final UUID id) {
        return query("id = cast(:key as uuid)", id.toString());
    }

    private Optional<Admin> query(final String where, final String key) {
        return tx.execute(s -> jdbc.sql("""
                        select id, email, password_hash, status, token_version, failed_logins, locked_until,
                               must_change_password, totp_enabled, totp_secret_enc
                        from identity_platform_admin where """ + " " + where)
                .param("key", key)
                .query((rs, n) -> new Admin(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getInt(5), rs.getInt(6), rs.getTimestamp(7) == null ? null : rs.getTimestamp(7).toInstant(),
                        rs.getBoolean(8), rs.getBoolean(9), rs.getBytes(10)))
                .optional());
    }
}
