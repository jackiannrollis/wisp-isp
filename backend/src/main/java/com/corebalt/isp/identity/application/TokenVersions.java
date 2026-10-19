package com.corebalt.isp.identity.application;

import com.corebalt.isp.shared.security.TokenVersionCheck;
import com.corebalt.isp.shared.security.Tokens;
import com.corebalt.isp.shared.tenant.TenantContext;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Per-request revocation (ADR 0010): the token's {@code ver} must equal the user's current token
 * version, read from Redis (10-minute cache) or, on a miss or a Redis failure, from the database;
 * never fail-open. An impersonation token is also refused once the impersonation has ended. Bumping
 * a version writes the database first and then the cache.
 */
@Component
public class TokenVersions implements TokenVersionCheck {

    private static final Logger log = LoggerFactory.getLogger(TokenVersions.class);
    private static final Duration CACHE = Duration.ofMinutes(10);

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final StringRedisTemplate redis;

    TokenVersions(final JdbcClient jdbc, final TransactionTemplate tx, final StringRedisTemplate redis) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.redis = redis;
    }

    @Override
    public boolean isCurrent(final Map<String, Object> claims) {
        final String type = (String) claims.get(Tokens.TYPE);
        final Object version = claims.get(Tokens.VERSION);
        final Object tenant = claims.get(Tokens.TENANT);
        final UUID subject = UUID.fromString((String) claims.get("sub"));
        if (!(version instanceof Number number) || type == null) {
            return false;
        }
        final UUID tenantId = tenant == null ? null : UUID.fromString(tenant.toString());
        final Optional<Integer> current = version(type, tenantId, subject);
        if (current.isEmpty() || current.get() != number.intValue()) {
            return false;
        }
        final Object impersonation = claims.get("imp");
        return impersonation == null || impersonationOpen(UUID.fromString(impersonation.toString()));
    }

    /** The current version, empty when the subject does not exist or may not sign in. */
    Optional<Integer> version(final String type, final UUID tenantId, final UUID subject) {
        final String key = "tokver:" + type + ":" + tenantId + ":" + subject;
        try {
            final String cached = redis.opsForValue().get(key);
            if (cached != null) {
                return cached.isEmpty() ? Optional.empty() : Optional.of(Integer.parseInt(cached));
            }
        } catch (RuntimeException e) {
            log.warn("Redis unavailable for token versions ({}); reading the database", e.getClass().getSimpleName());
        }
        final Optional<Integer> fromDb = load(type, tenantId, subject);
        try {
            redis.opsForValue().set(key, fromDb.map(String::valueOf).orElse(""), CACHE);
        } catch (RuntimeException e) {
            // the database answered; the cache is only an optimisation
        }
        return fromDb;
    }

    /** Disable, password or PIN change, role change: older tokens stop working at once. */
    void bump(final String type, final UUID tenantId, final UUID subject) {
        final String table = switch (type) {
            case Tokens.STAFF -> "identity_staff_user where id = :id";
            case Tokens.CUSTOMER -> "identity_customer_credential where customer_id = :id";
            case Tokens.PLATFORM -> "identity_platform_admin where id = :id";
            default -> throw new IllegalArgumentException(type);
        };
        jdbc.sql("update " + table.replace(" where", " set token_version = token_version + 1 where")).param("id", subject).update();
        forget(type, tenantId, subject);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            // A request between this delete and the commit may cache the old version again.
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    forget(type, tenantId, subject);
                }
            });
        }
    }

    void forget(final String type, final UUID tenantId, final UUID subject) {
        try {
            redis.delete("tokver:" + type + ":" + tenantId + ":" + subject);
        } catch (RuntimeException e) {
            log.warn("Redis unavailable: cached token version of {} expires within {}", subject, CACHE);
        }
    }

    private Optional<Integer> load(final String type, final UUID tenantId, final UUID subject) {
        return switch (type) {
            case Tokens.STAFF -> TenantContext.callAsTenant(tenantId, () -> tx.execute(s -> jdbc.sql(
                            "select token_version from identity_staff_user where id = :id and status = 'ACTIVE'")
                    .param("id", subject).query(Integer.class).optional()));
            case Tokens.CUSTOMER -> TenantContext.callAsTenant(tenantId, () -> tx.execute(s -> jdbc.sql(
                            "select token_version from identity_customer_credential where customer_id = :id")
                    .param("id", subject).query(Integer.class).optional()));
            case Tokens.PLATFORM -> TenantContext.callAsPlatform("token-version", () -> tx.execute(s -> jdbc.sql(
                            "select token_version from identity_platform_admin where id = :id and status = 'ACTIVE'")
                    .param("id", subject).query(Integer.class).optional()));
            default -> Optional.empty();
        };
    }

    private boolean impersonationOpen(final UUID impersonationId) {
        return Boolean.TRUE.equals(TenantContext.callAsPlatform("impersonation", () -> tx.execute(s -> jdbc.sql(
                        "select count(*) from identity_impersonation where id = :id and ended_at is null and expires_at > now()")
                .param("id", impersonationId).query(Long.class).single() == 1)));
    }
}
