package com.corebalt.isp.identity.application;

import com.corebalt.isp.shared.audit.AuditEntry;
import com.corebalt.isp.shared.id.UuidV7;
import com.corebalt.isp.shared.security.Permissions;
import com.corebalt.isp.shared.security.Tokens;
import com.corebalt.isp.shared.tenant.TenantContext;
import com.corebalt.isp.shared.web.ConflictException;
import com.corebalt.isp.shared.web.InvalidRequestException;
import com.corebalt.isp.shared.web.NotFoundException;
import com.corebalt.isp.tenancy.api.TenantRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A platform admin acting as a tenant's staff member (ADR 0010, A1): a separate access token for
 * that staff member, with {@code act} naming the admin and {@code imp} the impersonation, valid at
 * most 30 minutes and with no refresh token. Start and end (by the admin, by the impersonated session
 * or on expiry) are written to the tenant's own audit log, where the tenant sees them, and to the
 * platform audit log. An ended impersonation's token is refused on the next request.
 *
 * <p>The hand-off (ADR 0017): starting returns a single-use code, valid {@link #HANDOFF} and kept only as
 * its SHA-256, which the admin app exchanges for the token; the token never travels in a URL. The token
 * carries the staff member's permissions less {@link Permissions#BLOCKED_WHILE_IMPERSONATING}.
 */
@Service
public class Impersonations {

    public static final Duration MAX = Duration.ofMinutes(30);
    /** How long the hand-off code may wait to be exchanged (ADR 0017). */
    public static final Duration HANDOFF = Duration.ofSeconds(60);

    /** {@code openUrl}: the admin app's hand-off page with the code in the fragment, never sent to a server. */
    public record Started(UUID id, String code, Instant codeExpiresAt, Instant expiresAt, UUID tenantId, UUID staffUserId,
                          String openUrl) {}

    /** What the admin app gets for a code: the token (in memory only) and who acts as whom until when. */
    public record Exchanged(String accessToken, Instant expiresAt, UUID impersonationId, String staffName,
                            String platformAdminName) {}

    public record ImpersonationDto(UUID id, UUID platformAdminId, UUID tenantId, UUID staffUserId, String reason,
                                   Instant startedAt, Instant expiresAt, Instant endedAt) {}

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final TenantRegistry tenants;
    private final StaffSignIn staffSignIn;
    private final TokenIssuer tokens;
    private final JdbcAuditTrail tenantAudit;
    private final PlatformAudit platformAudit;
    private final Clock clock;
    private final String adminAppUrl;
    private final SecureRandom random = new SecureRandom();

    Impersonations(final JdbcClient jdbc, final TransactionTemplate tx, final TenantRegistry tenants,
                   final StaffSignIn staffSignIn, final TokenIssuer tokens, final JdbcAuditTrail tenantAudit,
                   final PlatformAudit platformAudit, final Clock clock,
                   @Value("${isp.admin-app-url:http://localhost:4200}") final String adminAppUrl) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.tenants = tenants;
        this.staffSignIn = staffSignIn;
        this.tokens = tokens;
        this.tenantAudit = tenantAudit;
        this.platformAudit = platformAudit;
        this.clock = clock;
        this.adminAppUrl = adminAppUrl.endsWith("/") ? adminAppUrl.substring(0, adminAppUrl.length() - 1) : adminAppUrl;
    }

    public Started start(final UUID adminId, final UUID tenantId, final UUID staffUserId, final String reason,
                         final Integer minutes) {
        if (reason == null || reason.isBlank()) {
            throw InvalidRequestException.of("reason", "a reason or ticket reference is required: the tenant reads it in their audit log");
        }
        final Duration length = minutes == null ? MAX : Duration.ofMinutes(minutes);
        if (length.isNegative() || length.isZero() || length.compareTo(MAX) > 0) {
            throw InvalidRequestException.of("minutes", "1 to 30");
        }
        tenants.find(tenantId).orElseThrow(() -> new NotFoundException("tenant"));
        return TenantContext.callAsTenant(tenantId, () -> {
            final StaffSignIn.Staff staff = staffSignIn.loadById(staffUserId)
                    .filter(s -> "ACTIVE".equals(s.status()))
                    .orElseThrow(() -> new ConflictException("Only an active staff member can be impersonated."));
            final UUID id = UuidV7.next(clock);
            final Instant now = clock.instant();
            final Instant expires = now.plus(length);
            final String code = newCode();
            final Instant codeExpires = now.plus(HANDOFF);
            tx.executeWithoutResult(s -> {
                jdbc.sql("""
                                insert into identity_impersonation
                                    (id, platform_admin_id, tenant_id, staff_user_id, reason, started_at, expires_at,
                                     handoff_code_hash, handoff_expires_at)
                                values (:id, :admin, :tenantId, :staff, :reason, :now, :expires, :codeHash, :codeExpires)
                                """)
                        .param("id", id).param("admin", adminId).param("tenantId", tenantId).param("staff", staffUserId)
                        .param("reason", reason.strip()).param("now", Timestamp.from(now)).param("expires", Timestamp.from(expires))
                        .param("codeHash", hash(code)).param("codeExpires", Timestamp.from(codeExpires))
                        .update();
                tenantAudit.recordPlatform(adminId, staffUserId, new AuditEntry("identity.impersonation_started",
                        "staff_user", staffUserId, null, Map.of("impersonationId", id.toString(), "expiresAt", expires.toString()),
                        reason.strip()));
                platformAudit.record(adminId, "platform.impersonation_started", "staff_user", staffUserId,
                        Map.of("tenantId", tenantId.toString(), "impersonationId", id.toString(), "reason", reason.strip()));
            });
            return new Started(id, code, codeExpires, expires, tenantId, staffUserId, adminAppUrl + "/impersonate#code=" + code);
        });
    }

    /**
     * The admin app's side of the hand-off: a code is good once, within {@link #HANDOFF}, for an impersonation
     * not yet ended or expired. Marking it used is one conditional UPDATE, so two exchanges of one code cannot
     * both win. The token lives until the impersonation's own end, with no refresh token and no cookie.
     */
    public Exchanged exchange(final String code) {
        if (code == null || code.isBlank() || code.length() > 100) {
            throw new AuthException(HANDOFF_REFUSED);
        }
        final Instant now = clock.instant();
        final ImpersonationDto row = TenantContext.callAsPlatform("impersonation-handoff", () -> tx.execute(s -> jdbc.sql("""
                        update identity_impersonation set handoff_used_at = :now
                        where handoff_code_hash = :hash and handoff_used_at is null and handoff_expires_at > :now
                          and ended_at is null and expires_at > :now
                        returning id, platform_admin_id, tenant_id, staff_user_id, reason, started_at, expires_at, ended_at
                        """)
                .param("now", Timestamp.from(now))
                .param("hash", hash(code.strip()))
                .query((rs, n) -> map(rs))
                .optional()))
                .orElseThrow(() -> new AuthException(HANDOFF_REFUSED));
        return TenantContext.callAsTenant(row.tenantId(), () -> {
            final StaffSignIn.Staff staff = staffSignIn.loadById(row.staffUserId())
                    .filter(s -> "ACTIVE".equals(s.status()))
                    .orElseThrow(() -> new AuthException(HANDOFF_REFUSED));
            final TokenIssuer.Subject full = StaffSignIn.subject(staff);
            final TokenIssuer.Subject subject = new TokenIssuer.Subject(full.type(), full.id(), full.tenantId(), full.role(),
                    Permissions.allowedWhileImpersonating(full.permissions()), full.version());
            final String token = tokens.access(subject, Duration.between(now, row.expiresAt()), Map.of(
                    Tokens.ACTOR, Map.of("sub", row.platformAdminId().toString(), Tokens.TYPE, Tokens.PLATFORM),
                    "imp", row.id().toString()));
            final String staffName = tx.execute(s -> jdbc.sql("select display_name from identity_staff_user where id = :id")
                    .param("id", row.staffUserId()).query(String.class).single());
            final String adminName = TenantContext.callAsPlatform("impersonation-handoff", () -> tx.execute(s -> jdbc.sql(
                            "select display_name from identity_platform_admin where id = :id")
                    .param("id", row.platformAdminId()).query(String.class).single()));
            return new Exchanged(token, row.expiresAt(), row.id(), staffName, adminName);
        });
    }

    /** The impersonated session's End button: the impersonation in its own token ({@code imp}) ends now. */
    public ImpersonationDto endFromSession(final UUID impersonationId, final UUID platformAdminId) {
        final ImpersonationDto row = find(impersonationId);
        if (!row.platformAdminId().equals(platformAdminId)) {
            throw new NotFoundException("impersonation");
        }
        return end(platformAdminId, impersonationId);
    }

    static final String HANDOFF_REFUSED = "This hand-off link is used, expired or ended. Start the impersonation again from the console.";

    private String newCode() {
        final byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String hash(final String code) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(code.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public ImpersonationDto end(final UUID adminId, final UUID id) {
        final ImpersonationDto row = find(id);
        if (row.endedAt() == null) {
            close(row, adminId, "identity.impersonation_ended");
        }
        return find(id);
    }

    public List<ImpersonationDto> recent() {
        return TenantContext.callAsPlatform("impersonations", () -> tx.execute(s -> jdbc.sql(SELECT + " order by started_at desc limit 100")
                .query((rs, n) -> map(rs)).list()));
    }

    /** Expired impersonations get their end row in both audit logs. */
    @Scheduled(fixedDelayString = "${isp.identity.impersonation-sweep:60s}", initialDelayString = "60s")
    public void closeExpired() {
        final List<ImpersonationDto> expired = TenantContext.callAsPlatform("impersonations", () -> tx.execute(s -> jdbc.sql(
                        SELECT + " where ended_at is null and expires_at <= :now")
                .param("now", Timestamp.from(clock.instant()))
                .query((rs, n) -> map(rs)).list()));
        expired.forEach(row -> close(row, null, "identity.impersonation_expired"));
    }

    private void close(final ImpersonationDto row, final UUID endedBy, final String action) {
        final Instant ended = endedBy == null ? row.expiresAt() : clock.instant();
        TenantContext.runAsTenant(row.tenantId(), () -> tx.executeWithoutResult(s -> {
            final int updated = jdbc.sql("update identity_impersonation set ended_at = :ended where id = :id and ended_at is null")
                    .param("ended", Timestamp.from(ended)).param("id", row.id()).update();
            if (updated == 0) {
                return;
            }
            tenantAudit.recordPlatform(row.platformAdminId(), row.staffUserId(), new AuditEntry(action, "staff_user",
                    row.staffUserId(), null, Map.of("impersonationId", row.id().toString()), null));
            platformAudit.record(endedBy == null ? row.platformAdminId() : endedBy, action.replace("identity.", "platform."),
                    "staff_user", row.staffUserId(), Map.of("tenantId", row.tenantId().toString(), "impersonationId", row.id().toString()));
        }));
    }

    private ImpersonationDto find(final UUID id) {
        return TenantContext.callAsPlatform("impersonations", () -> tx.execute(s -> jdbc.sql(SELECT + " where id = :id")
                .param("id", id).query((rs, n) -> map(rs)).optional()))
                .orElseThrow(() -> new NotFoundException("impersonation"));
    }

    private static final String SELECT = """
            select id, platform_admin_id, tenant_id, staff_user_id, reason, started_at, expires_at, ended_at
            from identity_impersonation""";

    private static ImpersonationDto map(final java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ImpersonationDto(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class),
                rs.getObject(4, UUID.class), rs.getString(5), rs.getTimestamp(6).toInstant(), rs.getTimestamp(7).toInstant(),
                rs.getTimestamp(8) == null ? null : rs.getTimestamp(8).toInstant());
    }
}
