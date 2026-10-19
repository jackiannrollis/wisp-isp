package com.corebalt.isp.shared.web;

import com.corebalt.isp.shared.tenant.TenantContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Idempotency keys for API writes that move money or change access (conventions.md, API). The key
 * row is inserted in the same transaction as the business change and holds the response, so:
 *
 * <ul>
 *   <li>a replay with the same key and request gets the original response back;
 *   <li>the same key with a different request is refused (422);
 *   <li>a concurrent duplicate waits on the key's unique index until the first commits, then
 *       replays its response;
 *   <li>a request that fails rolls back its key and may be retried.
 * </ul>
 *
 * Never use it for responses that carry a secret: a replay would return the secret again
 * (decisions.md 2026-10-19).
 */
@Component
public class IdempotentRequests {

    public static final String HEADER = "Idempotency-Key";
    public static final String REPLAYED_HEADER = "Idempotent-Replayed";

    private final JdbcClient jdbc;
    private final JsonMapper jsonMapper;

    public IdempotentRequests(final JdbcClient jdbc, final JsonMapper jsonMapper) {
        this.jdbc = jdbc;
        this.jsonMapper = jsonMapper;
    }

    /**
     * Runs {@code action} once per key. {@code operation} names the endpoint and target (for
     * example {@code POST /api/v1/subscriptions/<id>/pause}) and {@code request} its body (or null);
     * together they are the fingerprint a replay must match.
     */
    @Transactional
    public <T> Result<T> execute(
            final String key,
            final String operation,
            final Object request,
            final Class<T> responseType,
            final Supplier<T> action) {
        if (key == null || key.isBlank() || key.length() > 100) {
            throw InvalidRequestException.of(HEADER, "required, 1 to 100 characters");
        }
        final String fingerprint = fingerprint(operation, request);
        final int inserted =
                jdbc.sql(
                                """
                                insert into shared_idempotency_key (tenant_id, idempotency_key, fingerprint)
                                values (:tenantId, :key, :fingerprint)
                                on conflict (tenant_id, idempotency_key) do nothing
                                """)
                        .param("tenantId", TenantContext.requireTenantId())
                        .param("key", key)
                        .param("fingerprint", fingerprint)
                        .update();
        if (inserted == 1) {
            final T response = action.get();
            jdbc.sql(
                            """
                            update shared_idempotency_key
                            set response_status = 200, response_body = cast(:body as jsonb)
                            where tenant_id = :tenantId and idempotency_key = :key
                            """)
                    .param("body", jsonMapper.writeValueAsString(response))
                    .param("tenantId", TenantContext.requireTenantId())
                    .param("key", key)
                    .update();
            return new Result<>(response, false);
        }
        final Map<String, Object> stored =
                jdbc.sql(
                                """
                                select fingerprint, response_body::text as body
                                from shared_idempotency_key
                                where tenant_id = :tenantId and idempotency_key = :key
                                """)
                        .param("tenantId", TenantContext.requireTenantId())
                        .param("key", key)
                        .query()
                        .singleRow();
        if (!fingerprint.equals(stored.get("fingerprint"))) {
            throw new KeyReusedException();
        }
        return Optional.ofNullable((String) stored.get("body"))
                .map(body -> new Result<>(jsonMapper.readValue(body, responseType), true))
                .orElseThrow(() -> new ConflictException("A request with this Idempotency-Key is still in progress."));
    }

    private String fingerprint(final String operation, final Object request) {
        final String canonical = operation + "\n" + (request == null ? "" : jsonMapper.writeValueAsString(request));
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The response, and whether it is a replay of an earlier request. */
    public record Result<T>(T body, boolean replayed) {}

    /** The key was used before for a different request (422). */
    public static class KeyReusedException extends RuntimeException {

        KeyReusedException() {
            super("This Idempotency-Key was already used for a different request.");
        }
    }
}
