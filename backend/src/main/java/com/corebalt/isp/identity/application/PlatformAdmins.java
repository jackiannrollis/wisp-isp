package com.corebalt.isp.identity.application;

import com.corebalt.isp.identity.domain.Credentials;
import com.corebalt.isp.shared.id.UuidV7;
import com.corebalt.isp.shared.security.Tokens;
import com.corebalt.isp.shared.tenant.TenantContext;
import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The first platform admin and the break-glass reset (ADR 0010). Bootstrap creates an admin from
 * the environment only while the platform-admin table is empty; once it is not, the variables are
 * ignored. The reset is a command run inside the container, never an HTTP endpoint. Both write the
 * platform audit log and force a password change and a fresh TOTP enrolment.
 */
@Service
public class PlatformAdmins {

    private static final Logger log = LoggerFactory.getLogger(PlatformAdmins.class);

    public enum Bootstrap { CREATED, IGNORED_NOT_EMPTY, NOT_CONFIGURED }

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final TokenVersions versions;
    private final PlatformAudit audit;
    private final Clock clock;

    PlatformAdmins(final JdbcClient jdbc, final TransactionTemplate tx, final TokenVersions versions,
                   final PlatformAudit audit, final Clock clock) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.versions = versions;
        this.audit = audit;
        this.clock = clock;
    }

    public Bootstrap bootstrap(final String email, final String password) {
        if (email == null || email.isBlank() || password == null || password.isBlank()) {
            return Bootstrap.NOT_CONFIGURED;
        }
        return TenantContext.callAsPlatform("platform-bootstrap", () -> tx.execute(s -> {
            // Serialises two instances starting together.
            jdbc.sql("lock table identity_platform_admin in share row exclusive mode").update();
            if (jdbc.sql("select count(*) from identity_platform_admin").query(Long.class).single() > 0) {
                log.warn("ISP_BOOTSTRAP_ADMIN_EMAIL and ISP_BOOTSTRAP_ADMIN_PASSWORD are set but platform admins already "
                        + "exist, so they were ignored. Remove them from the environment.");
                return Bootstrap.IGNORED_NOT_EMPTY;
            }
            if (!Credentials.acceptablePassword(password, email)) {
                throw new IllegalStateException("ISP_BOOTSTRAP_ADMIN_PASSWORD must be at least "
                        + Credentials.MIN_PASSWORD + " characters and not the email");
            }
            final UUID id = UuidV7.next(clock);
            jdbc.sql("""
                            insert into identity_platform_admin (id, email, display_name, password_hash, must_change_password)
                            values (:id, :email, :name, :hash, true)
                            """)
                    .param("id", id)
                    .param("email", email.strip())
                    .param("name", "Platform admin")
                    .param("hash", Credentials.hash(password))
                    .update();
            audit.record(null, "platform.admin_bootstrapped", "platform_admin", id, Map.of("email", email.strip()));
            log.warn("Created the first platform admin {} from the environment. Sign in, change the password and enrol "
                    + "TOTP, then REMOVE ISP_BOOTSTRAP_ADMIN_EMAIL and ISP_BOOTSTRAP_ADMIN_PASSWORD from the environment.",
                    email.strip());
            return Bootstrap.CREATED;
        }));
    }

    /**
     * Break-glass: a new temporary password (returned once, for the operator's terminal only), TOTP
     * and recovery codes cleared so the admin enrols again, the account unlocked and enabled, every
     * session ended. Empty when no admin has that email.
     */
    public Optional<String> breakGlassReset(final String email, final String operator) {
        return TenantContext.callAsPlatform("platform-break-glass", () -> tx.execute(s -> {
            final Optional<UUID> id = jdbc.sql("select id from identity_platform_admin where lower(email) = lower(:email)")
                    .param("email", email == null ? "" : email.strip()).query(UUID.class).optional();
            if (id.isEmpty()) {
                return Optional.<String>empty();
            }
            final String temporary = Credentials.readable(16);
            jdbc.sql("""
                            update identity_platform_admin
                            set password_hash = :hash, must_change_password = true, totp_enabled = false,
                                totp_secret_enc = null, status = 'ACTIVE', failed_logins = 0, locked_until = null,
                                updated_at = now()
                            where id = :id
                            """)
                    .param("hash", Credentials.hash(temporary)).param("id", id.get()).update();
            jdbc.sql("update identity_platform_recovery_code set used_at = now() where admin_id = :id and used_at is null")
                    .param("id", id.get()).update();
            jdbc.sql("""
                            update identity_refresh_token set revoked_at = now()
                            where subject_type = 'PLATFORM' and subject_id = :id and revoked_at is null
                            """)
                    .param("id", id.get()).update();
            versions.bump(Tokens.PLATFORM, null, id.get());
            audit.record(null, "platform.admin_break_glass_reset", "platform_admin", id.get(),
                    Map.of("operator", operator == null ? "unknown" : operator));
            return Optional.of(temporary);
        }));
    }
}
