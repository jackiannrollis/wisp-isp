package com.corebalt.isp.notifications.application;

import com.corebalt.isp.shared.audit.AuditEntry;
import com.corebalt.isp.shared.audit.AuditTrail;
import com.corebalt.isp.shared.security.CurrentActor;
import com.corebalt.isp.shared.security.Permissions;
import com.corebalt.isp.shared.tenant.TenantContext;
import com.corebalt.isp.shared.web.NotFoundException;
import com.corebalt.isp.tenancy.api.SecretStore;
import com.corebalt.isp.tenancy.api.SecretStore.SecretKind;
import com.corebalt.isp.tenancy.api.TenantProfile;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalTime;
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
 * A tenant's Celcom Africa account and sending window (docs/07 per-tenant configuration). The
 * API key is a tenant secret (T4); the delivery-report URL carries a random token that is also a
 * secret, with only its hash in the platform route table.
 */
@Service
public class SmsSettings {

    private static final SecureRandom RANDOM = new SecureRandom();

    @Schema(name = "SmsSettingsRequest")
    public record SettingsRequest(
            @NotBlank @Size(max = 100) String partnerId,
            @NotBlank @Size(max = 11) @Pattern(regexp = "[A-Za-z0-9 _-]+") String senderId,
            Boolean enabled,
            @Pattern(regexp = "([01][0-9]|2[0-3]):[0-5][0-9]") String windowStart,
            @Pattern(regexp = "([01][0-9]|2[0-3]):[0-5][0-9]") String windowEnd,
            @Size(max = 200) String apiKey) {}

    @Schema(name = "SmsSettingsView")
    public record SettingsView(
            String provider, String partnerId, String senderId, boolean enabled, String windowStart, String windowEnd,
            String deliveryReportUrl) {}

    /** What the sender needs; never logged. */
    public record Account(String partnerId, String apiKey, String senderId) {

        @Override
        public String toString() {
            return "Account[provider=CELCOM_AFRICA]";
        }
    }

    record Stored(String provider, String partnerId, String senderId, boolean enabled, LocalTime start, LocalTime end) {}

    private final JdbcClient jdbc;
    private final SecretStore secrets;
    private final TenantProfile tenantProfile;
    private final AuditTrail audit;
    private final Clock clock;
    private final String callbackBaseUrl;

    SmsSettings(
            final JdbcClient jdbc,
            final SecretStore secrets,
            final TenantProfile tenantProfile,
            final AuditTrail audit,
            final Clock clock,
            @Value("${isp.mpesa.callback-base-url:http://localhost:8088}") final String callbackBaseUrl) {
        this.jdbc = jdbc;
        this.secrets = secrets;
        this.tenantProfile = tenantProfile;
        this.audit = audit;
        this.clock = clock;
        this.callbackBaseUrl = callbackBaseUrl.replaceAll("/+$", "");
    }

    /** The sender ID and reminder window alone (settings.write), without the Celcom account. */
    @Schema(name = "SmsSendingRequest")
    public record SendingRequest(
            @NotBlank @Size(max = 11) @Pattern(regexp = "[A-Za-z0-9 _-]+") String senderId,
            @NotNull @Pattern(regexp = "([01][0-9]|2[0-3]):[0-5][0-9]") String windowStart,
            @NotNull @Pattern(regexp = "([01][0-9]|2[0-3]):[0-5][0-9]") String windowEnd) {}

    /**
     * Changes the sender ID and the reminder sending window (M2) of an account already set up
     * (decisions.md 2026-10-21); audited like the account itself.
     */
    @Transactional
    public SettingsView updateSending(final SendingRequest request) {
        final UUID tenantId = TenantContext.requireTenantId();
        final LocalTime start = LocalTime.parse(request.windowStart());
        final LocalTime end = LocalTime.parse(request.windowEnd());
        if (!start.isBefore(end)) {
            throw com.corebalt.isp.shared.web.InvalidRequestException.of("windowEnd", "must be after windowStart");
        }
        final Stored before = stored().orElseThrow(() -> new NotFoundException("SMS settings"));
        jdbc.sql("update notifications_settings set sender_id = :sender, window_start = :start, window_end = :end, updated_at = :now")
                .param("sender", request.senderId().strip())
                .param("start", Time.valueOf(start))
                .param("end", Time.valueOf(end))
                .param("now", Timestamp.from(clock.instant()))
                .update();
        audit.record(new AuditEntry("notifications.sms_sending_changed", "tenant", tenantId,
                Map.of("senderId", before.senderId(), "windowStart", before.start().toString(), "windowEnd", before.end().toString()),
                Map.of("senderId", request.senderId().strip(), "windowStart", start.toString(), "windowEnd", end.toString()),
                null));
        return view();
    }

    /**
     * The Celcom Africa account (settings.secrets). Once it exists, the sender ID and the sending
     * window belong to the Owner (settings.write, decisions.md 2026-10-21): a caller without that
     * permission keeps the stored ones whatever the request says.
     */
    @Transactional
    public SettingsView configure(final SettingsRequest request) {
        final UUID tenantId = TenantContext.requireTenantId();
        final Optional<Stored> existing = stored();
        if ((request.apiKey() == null || request.apiKey().isBlank())
                && secrets.reveal(SecretKind.CELCOM_SMS_API_KEY).isEmpty()) {
            throw com.corebalt.isp.shared.web.InvalidRequestException.of("apiKey", "is required when connecting Celcom Africa");
        }
        final boolean ownerFields = existing.isEmpty() || CurrentActor.has(Permissions.SETTINGS_WRITE);
        final LocalTime start = ownerFields
                ? LocalTime.parse(Optional.ofNullable(request.windowStart()).orElse("07:00")) : existing.get().start();
        final LocalTime end = ownerFields
                ? LocalTime.parse(Optional.ofNullable(request.windowEnd()).orElse("21:00")) : existing.get().end();
        final String senderId = ownerFields ? request.senderId().strip() : existing.get().senderId();
        if (!start.isBefore(end)) {
            throw com.corebalt.isp.shared.web.InvalidRequestException.of("windowEnd", "must be after windowStart");
        }
        final int updated = jdbc.sql("""
                        update notifications_settings
                        set sms_provider = 'CELCOM_AFRICA', celcom_partner_id = :partner, sender_id = :sender,
                            enabled = coalesce(:enabled, enabled), window_start = :start, window_end = :end, updated_at = :now
                        """)
                .param("partner", request.partnerId().strip())
                .param("sender", senderId)
                .param("enabled", request.enabled())
                .param("start", Time.valueOf(start))
                .param("end", Time.valueOf(end))
                .param("now", Timestamp.from(clock.instant()))
                .update();
        if (updated == 0) {
            final byte[] bytes = new byte[24];
            RANDOM.nextBytes(bytes);
            final String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            secrets.put(SecretKind.SMS_CALLBACK_TOKEN, token);
            jdbc.sql("""
                            insert into notifications_settings
                                (tenant_id, at_username, sender_id, sandbox, enabled, window_start, window_end, callback_token_hash,
                                 sms_provider, celcom_partner_id)
                            values (:tenantId, '', :sender, false, coalesce(:enabled, true), :start, :end, :hash,
                                    'CELCOM_AFRICA', :partner)
                            """)
                    .param("tenantId", tenantId)
                    .param("partner", request.partnerId().strip())
                    .param("sender", request.senderId().strip())
                    .param("enabled", request.enabled())
                    .param("start", Time.valueOf(start))
                    .param("end", Time.valueOf(end))
                    .param("hash", hash(token))
                    .update();
            jdbc.sql("""
                            insert into notifications_callback_route (token_hash, tenant_id, sms_provider)
                            values (:hash, :tenantId, 'CELCOM_AFRICA')
                            on conflict (tenant_id) do update set token_hash = excluded.token_hash, sms_provider = excluded.sms_provider
                            """)
                    .param("hash", hash(token))
                    .param("tenantId", tenantId)
                    .update();
        }
        jdbc.sql("update notifications_callback_route set sms_provider = 'CELCOM_AFRICA' where tenant_id = :tenantId")
                .param("tenantId", tenantId).update();
        if (request.apiKey() != null && !request.apiKey().isBlank()) {
            secrets.put(SecretKind.CELCOM_SMS_API_KEY, request.apiKey().strip());
        }
        audit.record(AuditEntry.of("notifications.sms_configured", "tenant", tenantId,
                Map.of("senderId", senderId, "provider", "CELCOM_AFRICA")));
        return view();
    }

    @Transactional(readOnly = true)
    public SettingsView view() {
        final Stored stored = stored().orElseThrow(() -> new NotFoundException("SMS settings"));
        // ADR 0017: the token is a secret, so Corebalt support impersonating sees the settings without the URL.
        // Celcom's public docs do not specify the callback payload. Do not publish a legacy
        // Africa's Talking URL as though it were a verified Celcom delivery integration.
        final String deliveryReportUrl = CurrentActor.impersonating() || "CELCOM_AFRICA".equals(stored.provider())
                ? null
                : callbackBaseUrl + "/callbacks/sms/" + secrets.reveal(SecretKind.SMS_CALLBACK_TOKEN).orElse("") + "/delivery";
        return new SettingsView(stored.provider(), stored.partnerId(), stored.senderId(), stored.enabled(),
                stored.start().toString(), stored.end().toString(), deliveryReportUrl);
    }

    @Transactional(readOnly = true)
    Optional<Stored> stored() {
        return jdbc.sql("select sms_provider, celcom_partner_id, sender_id, enabled, window_start, window_end from notifications_settings")
                .query((rs, n) -> new Stored(rs.getString(1), rs.getString(2), rs.getString(3), rs.getBoolean(4),
                        rs.getTime(5).toLocalTime(), rs.getTime(6).toLocalTime()))
                .optional();
    }

    @Transactional(readOnly = true)
    Optional<Account> account() {
        return stored().filter(Stored::enabled).flatMap(s -> deliveryAccount());
    }

    /** Delivery lookup cannot send a message, so disabling sends does not hide existing reports. */
    @Transactional(readOnly = true)
    Optional<Account> deliveryAccount() {
        return stored().filter(s -> "CELCOM_AFRICA".equals(s.provider()))
                .flatMap(s -> secrets.reveal(SecretKind.CELCOM_SMS_API_KEY)
                .map(key -> new Account(s.partnerId(), key, s.senderId())));
    }

    /** M2: the tenant's window, or the default 07:00-21:00 when SMS is not configured yet. */
    @Transactional(readOnly = true)
    com.corebalt.isp.notifications.domain.SendingWindow window() {
        final var zone = tenantProfile.currentPolicy().zone();
        return stored()
                .map(s -> new com.corebalt.isp.notifications.domain.SendingWindow(s.start(), s.end(), zone))
                .orElse(new com.corebalt.isp.notifications.domain.SendingWindow(LocalTime.of(7, 0), LocalTime.of(21, 0), zone));
    }

    /** Platform scope: the tenant of a delivery-report token. */
    public Optional<UUID> tenantForToken(final String token) {
        if (token == null || token.length() < 20 || token.length() > 100) {
            return Optional.empty();
        }
        return jdbc.sql("""
                        select tenant_id from notifications_callback_route
                        where token_hash = :hash and sms_provider = 'AFRICAS_TALKING'
                        """)
                .param("hash", hash(token))
                .query(UUID.class)
                .optional();
    }

    static String hash(final String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
