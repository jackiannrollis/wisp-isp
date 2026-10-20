package com.corebalt.isp.payments.application;

import com.corebalt.isp.payments.infrastructure.daraja.DarajaClient;
import com.corebalt.isp.shared.audit.AuditEntry;
import com.corebalt.isp.shared.audit.AuditTrail;
import com.corebalt.isp.shared.tenant.TenantContext;
import com.corebalt.isp.shared.web.NotFoundException;
import com.corebalt.isp.tenancy.api.SecretStore;
import com.corebalt.isp.tenancy.api.SecretStore.SecretKind;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A tenant's Paybill or Till (docs/05 M-Pesa integration, docs/07 onboarding step 2). Credentials go
 * to the {@link SecretStore} (T4) and are never returned. Each tenant gets a random callback path
 * token, kept as a secret (STK Push sends it in every CallBackURL); the platform-wide route table
 * that resolves the tenant of an incoming callback holds only its SHA-256.
 */
@Service
public class MpesaSettings {

    private static final SecureRandom RANDOM = new SecureRandom();

    /** Write-only fields are optional on update: a blank one keeps the stored secret. */
    @Schema(name = "MpesaSettingsRequest")
    public record SettingsRequest(
            @NotBlank @Pattern(regexp = "[0-9]{5,7}") String shortcode,
            @NotNull @Pattern(regexp = "PAYBILL|TILL") String shortcodeType,
            @NotNull Boolean sandbox,
            @Size(max = 200) String consumerKey,
            @Size(max = 200) String consumerSecret,
            @Size(max = 200) String passkey,
            @Size(max = 100) String initiatorName,
            @Size(max = 2048) String securityCredential,
            Boolean validationEnabled) {}

    /** {@code callbackUrls} contain the tenant's path token; they are what Safaricom calls. */
    @Schema(name = "MpesaSettingsView")
    public record SettingsView(
            String shortcode, String shortcodeType, boolean sandbox, boolean validationEnabled,
            Instant urlsRegisteredAt, Map<String, String> callbackUrls) {}

    private final JdbcClient jdbc;
    private final SecretStore secrets;
    private final DarajaClient daraja;
    private final AuditTrail audit;
    private final Clock clock;
    private final String callbackBaseUrl;

    MpesaSettings(
            final JdbcClient jdbc,
            final SecretStore secrets,
            final DarajaClient daraja,
            final AuditTrail audit,
            final Clock clock,
            @Value("${isp.mpesa.callback-base-url:http://localhost:8088}") final String callbackBaseUrl) {
        this.jdbc = jdbc;
        this.secrets = secrets;
        this.daraja = daraja;
        this.audit = audit;
        this.clock = clock;
        this.callbackBaseUrl = callbackBaseUrl.replaceAll("/+$", "");
    }

    /** Saves the settings and secrets; the callback token is issued on first save. */
    @Transactional
    public SettingsView configure(final SettingsRequest request) {
        final UUID tenantId = TenantContext.requireTenantId();
        final Instant now = clock.instant();
        final int updated = jdbc.sql("""
                        update payments_mpesa_config
                        set shortcode = :shortcode, shortcode_type = :type, sandbox = :sandbox,
                            validation_enabled = coalesce(:validation, validation_enabled), updated_at = :now
                        """)
                .param("shortcode", request.shortcode())
                .param("type", request.shortcodeType())
                .param("sandbox", request.sandbox())
                .param("validation", request.validationEnabled())
                .param("now", Timestamp.from(now))
                .update();
        if (updated == 0) {
            final String token = newToken();
            secrets.put(SecretKind.MPESA_CALLBACK_TOKEN, token);
            final String hash = hash(token);
            jdbc.sql("""
                            insert into payments_mpesa_config
                                (tenant_id, shortcode, shortcode_type, sandbox, callback_token_hash, validation_enabled)
                            values (:tenantId, :shortcode, :type, :sandbox, :hash, coalesce(:validation, false))
                            """)
                    .param("tenantId", tenantId)
                    .param("shortcode", request.shortcode())
                    .param("type", request.shortcodeType())
                    .param("sandbox", request.sandbox())
                    .param("hash", hash)
                    .param("validation", request.validationEnabled())
                    .update();
            jdbc.sql("""
                            insert into payments_callback_route (token_hash, tenant_id) values (:hash, :tenantId)
                            on conflict (tenant_id) do update set token_hash = excluded.token_hash
                            """)
                    .param("hash", hash)
                    .param("tenantId", tenantId)
                    .update();
        }
        store(SecretKind.MPESA_CONSUMER_KEY, request.consumerKey());
        store(SecretKind.MPESA_CONSUMER_SECRET, request.consumerSecret());
        store(SecretKind.MPESA_PASSKEY, request.passkey());
        store(SecretKind.MPESA_INITIATOR_NAME, request.initiatorName());
        store(SecretKind.MPESA_SECURITY_CREDENTIAL, request.securityCredential());
        audit.record(AuditEntry.of("payments.mpesa_configured", "tenant", tenantId,
                Map.of("shortcode", request.shortcode(), "sandbox", request.sandbox())));
        return view();
    }

    @Transactional(readOnly = true)
    public SettingsView view() {
        final Config config = config().orElseThrow(() -> new NotFoundException("M-Pesa settings"));
        return new SettingsView(config.shortcode(), config.type(), config.sandbox(), config.validationEnabled(),
                config.registeredAt(), urls(token()));
    }

    /** C2B RegisterURL for the tenant's shortcode (docs/05 APIs used). */
    @Transactional
    public SettingsView registerUrls() {
        final Map<String, String> urls = urls(token());
        daraja.registerUrls(credentials(), urls.get("c2bConfirmation"), urls.get("c2bValidation"));
        jdbc.sql("update payments_mpesa_config set urls_registered_at = :now, updated_at = :now")
                .param("now", Timestamp.from(clock.instant()))
                .update();
        audit.record(AuditEntry.of("payments.mpesa_urls_registered", "tenant", TenantContext.requireTenantId()));
        return view();
    }

    /** For Daraja calls; the result is never logged or returned. */
    @Transactional(readOnly = true)
    public DarajaClient.Credentials credentials() {
        final Config config = config().orElseThrow(() -> new NotFoundException("M-Pesa settings"));
        return new DarajaClient.Credentials(
                config.sandbox(),
                config.shortcode(),
                "TILL".equals(config.type()),
                secrets.reveal(SecretKind.MPESA_CONSUMER_KEY).orElseThrow(() -> missing("consumer key")),
                secrets.reveal(SecretKind.MPESA_CONSUMER_SECRET).orElseThrow(() -> missing("consumer secret")),
                secrets.reveal(SecretKind.MPESA_PASSKEY).orElse(null),
                secrets.reveal(SecretKind.MPESA_INITIATOR_NAME).orElse(null),
                secrets.reveal(SecretKind.MPESA_SECURITY_CREDENTIAL).orElse(null));
    }

    /** The URL Daraja calls for one kind of callback, e.g. {@code stk}. */
    @Transactional(readOnly = true)
    public String callbackUrl(final String path) {
        return url(token(), path);
    }

    private String url(final String token, final String path) {
        return callbackBaseUrl + "/callbacks/mpesa/" + token + "/" + path;
    }

    private String token() {
        return secrets.reveal(SecretKind.MPESA_CALLBACK_TOKEN).orElseThrow(() -> missing("callback token"));
    }

    /** Platform scope: which tenant a callback token belongs to (no tenant context yet). */
    public Optional<UUID> tenantForToken(final String token) {
        if (token == null || token.length() < 20 || token.length() > 100) {
            return Optional.empty();
        }
        return jdbc.sql("select tenant_id from payments_callback_route where token_hash = :hash")
                .param("hash", hash(token))
                .query(UUID.class)
                .optional();
    }

    Optional<Config> config() {
        return jdbc.sql("select shortcode, shortcode_type, sandbox, callback_token_hash, validation_enabled, urls_registered_at from payments_mpesa_config")
                .query((rs, n) -> new Config(rs.getString(1), rs.getString(2), rs.getBoolean(3), rs.getString(4),
                        rs.getBoolean(5), rs.getTimestamp(6) == null ? null : rs.getTimestamp(6).toInstant()))
                .optional();
    }

    private Map<String, String> urls(final String token) {
        return Map.of(
                "c2bConfirmation", url(token, "c2b/confirmation"),
                "c2bValidation", url(token, "c2b/validation"),
                "stk", url(token, "stk"),
                "transactionStatusResult", url(token, "txn-status/result"),
                "transactionStatusTimeout", url(token, "txn-status/timeout"));
    }

    private void store(final SecretKind kind, final String value) {
        if (value != null && !value.isBlank()) {
            secrets.put(kind, value.strip());
        }
    }

    private static IllegalStateException missing(final String what) {
        return new IllegalStateException("M-Pesa " + what + " is not set for this tenant");
    }

    private static String newToken() {
        final byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String hash(final String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    record Config(String shortcode, String type, boolean sandbox, String tokenHash, boolean validationEnabled, Instant registeredAt) {}
}
