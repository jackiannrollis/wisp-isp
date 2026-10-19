package com.corebalt.isp.identity.application;

import com.corebalt.isp.identity.domain.Credentials;
import com.corebalt.isp.shared.id.UuidV7;
import com.corebalt.isp.shared.tenant.TenantContext;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Rotating refresh tokens (ADR 0010): opaque, stored as SHA-256, grouped in a family per sign-in.
 * Each use rotates the token; presenting a token that was already rotated means it was copied, so
 * the whole family is revoked (theft detection). Platform-scoped table: looked up before any tenant
 * is known, holding hashes and IDs only.
 */
@Component
public class RefreshTokens {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokens.class);

    public static final Duration STAFF_TTL = Duration.ofDays(7);
    public static final Duration CUSTOMER_TTL = Duration.ofDays(90);
    public static final Duration PLATFORM_TTL = Duration.ofHours(12);

    public record Issued(String token, Duration ttl) {}

    public record Owner(String subjectType, UUID subjectId, UUID tenantId) {}

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;

    RefreshTokens(final JdbcClient jdbc, final TransactionTemplate tx, final Clock clock) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.clock = clock;
    }

    /** A new family, at sign-in. */
    public Issued issue(final String subjectType, final UUID subjectId, final UUID tenantId) {
        return insert(UuidV7.next(clock), subjectType, subjectId, tenantId);
    }

    /** Rotates {@code token}; empty when it is unknown, expired or revoked, or was already rotated (family revoked). */
    public Optional<Rotated> rotate(final String token) {
        final String hash = Credentials.sha256(token);
        return TenantContext.callAsPlatform("refresh-token", () -> tx.execute(s -> {
            final Optional<Row> row = jdbc.sql("""
                            select id, family_id, subject_type, subject_id, tenant_id, expires_at, rotated_at, revoked_at
                            from identity_refresh_token where token_hash = :hash for update
                            """)
                    .param("hash", hash)
                    .query((rs, n) -> new Row(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                            rs.getObject(4, UUID.class), rs.getObject(5, UUID.class), rs.getTimestamp(6).toInstant(),
                            rs.getTimestamp(7) != null, rs.getTimestamp(8) != null))
                    .optional();
            if (row.isEmpty()) {
                return Optional.<Rotated>empty();
            }
            final Row current = row.get();
            final Instant now = clock.instant();
            if (current.rotated()) {
                log.warn("Refresh token reused after rotation: family {} revoked", current.familyId());
                revoke(current.familyId(), now);
                return Optional.<Rotated>empty();
            }
            if (current.revoked() || !now.isBefore(current.expiresAt())) {
                return Optional.<Rotated>empty();
            }
            jdbc.sql("update identity_refresh_token set rotated_at = :now where id = :id")
                    .param("now", Timestamp.from(now)).param("id", current.id()).update();
            final Issued next = insert(current.familyId(), current.subjectType(), current.subjectId(), current.tenantId());
            return Optional.of(new Rotated(new Owner(current.subjectType(), current.subjectId(), current.tenantId()), next));
        }));
    }

    /** Sign-out: the family of {@code token} can no longer be used. */
    public void revokeFamilyOf(final String token) {
        TenantContext.callAsPlatform("refresh-token", () -> tx.execute(s -> {
            jdbc.sql("select family_id from identity_refresh_token where token_hash = :hash")
                    .param("hash", Credentials.sha256(token))
                    .query(UUID.class)
                    .optional()
                    .ifPresent(family -> revoke(family, clock.instant()));
            return null;
        }));
    }

    /** Every session of a user ends: disable, password reset, role change. */
    public void revokeSubject(final String subjectType, final UUID subjectId) {
        TenantContext.callAsPlatform("refresh-token", () -> tx.execute(s -> jdbc.sql("""
                        update identity_refresh_token set revoked_at = :now
                        where subject_type = :type and subject_id = :subject and revoked_at is null
                        """)
                .param("now", Timestamp.from(clock.instant()))
                .param("type", subjectType)
                .param("subject", subjectId)
                .update()));
    }

    public record Rotated(Owner owner, Issued issued) {}

    private Issued insert(final UUID familyId, final String subjectType, final UUID subjectId, final UUID tenantId) {
        final String token = Credentials.opaque();
        final Duration ttl = switch (subjectType) {
            case "CUSTOMER" -> CUSTOMER_TTL;
            case "PLATFORM" -> PLATFORM_TTL;
            default -> STAFF_TTL;
        };
        final Instant now = clock.instant();
        TenantContext.callAsPlatform("refresh-token", () -> tx.execute(s -> jdbc.sql("""
                        insert into identity_refresh_token
                            (id, family_id, subject_type, subject_id, tenant_id, token_hash, expires_at, created_at)
                        values (:id, :family, :type, :subject, :tenant, :hash, :expires, :now)
                        """)
                .param("id", UuidV7.next(clock))
                .param("family", familyId)
                .param("type", subjectType)
                .param("subject", subjectId)
                .param("tenant", tenantId)
                .param("hash", Credentials.sha256(token))
                .param("expires", Timestamp.from(now.plus(ttl)))
                .param("now", Timestamp.from(now))
                .update()));
        return new Issued(token, ttl);
    }

    private void revoke(final UUID familyId, final Instant now) {
        jdbc.sql("update identity_refresh_token set revoked_at = :now where family_id = :family and revoked_at is null")
                .param("now", Timestamp.from(now)).param("family", familyId).update();
    }

    private record Row(UUID id, UUID familyId, String subjectType, UUID subjectId, UUID tenantId, Instant expiresAt,
                       boolean rotated, boolean revoked) {}
}
