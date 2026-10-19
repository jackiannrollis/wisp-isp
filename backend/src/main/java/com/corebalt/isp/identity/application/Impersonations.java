package com.corebalt.isp.identity.application;

import com.corebalt.isp.shared.audit.AuditEntry;
import com.corebalt.isp.shared.id.UuidV7;
import com.corebalt.isp.shared.security.Tokens;
import com.corebalt.isp.shared.tenant.TenantContext;
import com.corebalt.isp.shared.web.ConflictException;
import com.corebalt.isp.shared.web.InvalidRequestException;
import com.corebalt.isp.shared.web.NotFoundException;
import com.corebalt.isp.tenancy.api.TenantRegistry;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A platform admin acting as a tenant's staff member (ADR 0010, A1): a separate access token for
 * that staff member, with {@code act} naming the admin and {@code imp} the impersonation, valid at
 * most 30 minutes and with no refresh token. Start and end (by the admin, or on expiry) are written
 * to the tenant's own audit log, where the tenant sees them, and to the platform audit log. An
 * ended impersonation's token is refused on the next request.
 */
@Service
public class Impersonations {

    public static final Duration MAX = Duration.ofMinutes(30);

    public record Started(UUID id, String accessToken, Instant expiresAt, UUID tenantId, UUID staffUserId) {}

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

    Impersonations(final JdbcClient jdbc, final TransactionTemplate tx, final TenantRegistry tenants,
                   final StaffSignIn staffSignIn, final TokenIssuer tokens, final JdbcAuditTrail tenantAudit,
                   final PlatformAudit platformAudit, final Clock clock) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.tenants = tenants;
        this.staffSignIn = staffSignIn;
        this.tokens = tokens;
        this.tenantAudit = tenantAudit;
        this.platformAudit = platformAudit;
        this.clock = clock;
    }

    public Started start(final UUID adminId, final UUID tenantId, final UUID staffUserId, final String reason,
                         final Integer minutes) {
        if (reason == null || reason.isBlank()) {
            throw InvalidRequestException.of("reason", "required: the tenant reads it in their audit log");
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
            tx.executeWithoutResult(s -> {
                jdbc.sql("""
                                insert into identity_impersonation
                                    (id, platform_admin_id, tenant_id, staff_user_id, reason, started_at, expires_at)
                                values (:id, :admin, :tenantId, :staff, :reason, :now, :expires)
                                """)
                        .param("id", id).param("admin", adminId).param("tenantId", tenantId).param("staff", staffUserId)
                        .param("reason", reason.strip()).param("now", Timestamp.from(now)).param("expires", Timestamp.from(expires))
                        .update();
                tenantAudit.recordPlatform(adminId, staffUserId, new AuditEntry("identity.impersonation_started",
                        "staff_user", staffUserId, null, Map.of("impersonationId", id.toString(), "expiresAt", expires.toString()),
                        reason.strip()));
                platformAudit.record(adminId, "platform.impersonation_started", "staff_user", staffUserId,
                        Map.of("tenantId", tenantId.toString(), "impersonationId", id.toString(), "reason", reason.strip()));
            });
            final String token = tokens.access(StaffSignIn.subject(staff), length, Map.of(
                    Tokens.ACTOR, Map.of("sub", adminId.toString(), Tokens.TYPE, Tokens.PLATFORM),
                    "imp", id.toString()));
            return new Started(id, token, expires, tenantId, staffUserId);
        });
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
