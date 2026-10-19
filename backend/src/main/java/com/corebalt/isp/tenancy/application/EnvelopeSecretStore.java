package com.corebalt.isp.tenancy.application;

import com.corebalt.isp.shared.audit.AuditEntry;
import com.corebalt.isp.shared.audit.AuditTrail;
import com.corebalt.isp.shared.tenant.TenantContext;
import com.corebalt.isp.tenancy.api.SecretStore;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Envelope encryption (docs/07): a random 256-bit data key per tenant, stored only wrapped by the
 * platform master key with AES-256-GCM; secrets encrypted with the data key, AES-256-GCM, with the
 * tenant and kind as associated data so a ciphertext cannot be moved to another tenant or kind.
 * The master key comes from {@code ISP_MASTER_KEY} (base64, 32 bytes) and never reaches the
 * database; without it, using a secret fails rather than storing anything in clear.
 */
@Service
class EnvelopeSecretStore implements SecretStore {

    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcClient jdbc;
    private final AuditTrail audit;
    private final Clock clock;
    private final byte[] masterKey;
    private final String masterKeyId;

    EnvelopeSecretStore(
            final JdbcClient jdbc,
            final AuditTrail audit,
            final Clock clock,
            @Value("${isp.secrets.master-key:}") final String masterKeyBase64) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.clock = clock;
        this.masterKey = masterKeyBase64.isBlank() ? null : decode(masterKeyBase64);
        if (masterKey != null && masterKey.length != 32) {
            throw new IllegalStateException("ISP_MASTER_KEY must be 32 bytes, base64-encoded");
        }
        // A fingerprint, not the key: tells which master key wrapped a data key, for rotation.
        this.masterKeyId = masterKey == null ? null : fingerprint(masterKey);
    }

    @Override
    @Transactional
    public void put(final SecretKind kind, final String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("a secret cannot be blank");
        }
        final UUID tenantId = TenantContext.requireTenantId();
        final DataKey dataKey = currentDataKey(tenantId);
        final byte[] nonce = nonce();
        final byte[] ciphertext = encrypt(dataKey.key(), nonce, aad(tenantId, kind.name()), value.getBytes(StandardCharsets.UTF_8));
        final int updated =
                jdbc.sql("""
                                update tenancy_tenant_secret
                                set ciphertext = :ciphertext, nonce = :nonce, key_version = :version, updated_at = :now
                                where kind = :kind
                                """)
                        .param("ciphertext", ciphertext)
                        .param("nonce", nonce)
                        .param("version", dataKey.version())
                        .param("now", Timestamp.from(clock.instant()))
                        .param("kind", kind.name())
                        .update();
        if (updated == 0) {
            jdbc.sql("""
                            insert into tenancy_tenant_secret (tenant_id, kind, ciphertext, nonce, key_version)
                            values (:tenantId, :kind, :ciphertext, :nonce, :version)
                            """)
                    .param("tenantId", tenantId)
                    .param("kind", kind.name())
                    .param("ciphertext", ciphertext)
                    .param("nonce", nonce)
                    .param("version", dataKey.version())
                    .update();
        }
        // A1: secret change audited with the kind only (A3, M3).
        audit.record(AuditEntry.of("tenancy.secret_replaced", "tenant_secret", tenantId, Map.of("kind", kind.name())));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> reveal(final SecretKind kind) {
        final UUID tenantId = TenantContext.requireTenantId();
        return jdbc.sql("""
                        select s.ciphertext, s.nonce, k.wrapped_key, k.wrap_nonce, k.version
                        from tenancy_tenant_secret s
                        join tenancy_tenant_data_key k on k.tenant_id = s.tenant_id and k.version = s.key_version
                        where s.kind = :kind
                        """)
                .param("kind", kind.name())
                .query((rs, n) -> {
                    final byte[] key = unwrap(tenantId, rs.getInt(5), rs.getBytes(3), rs.getBytes(4));
                    return new String(decrypt(key, rs.getBytes(2), aad(tenantId, kind.name()), rs.getBytes(1)), StandardCharsets.UTF_8);
                })
                .optional();
    }

    @Override
    @Transactional(readOnly = true)
    public List<StoredSecret> list() {
        return jdbc.sql("select kind, key_version, updated_at from tenancy_tenant_secret order by kind")
                .query((rs, n) -> new StoredSecret(
                        SecretKind.valueOf(rs.getString(1)), rs.getInt(2), rs.getTimestamp(3).toInstant()))
                .list();
    }

    private DataKey currentDataKey(final UUID tenantId) {
        final Optional<DataKey> existing =
                jdbc.sql("""
                                select version, wrapped_key, wrap_nonce from tenancy_tenant_data_key
                                order by version desc limit 1
                                """)
                        .query((rs, n) -> new DataKey(rs.getInt(1), unwrap(tenantId, rs.getInt(1), rs.getBytes(2), rs.getBytes(3))))
                        .optional();
        if (existing.isPresent()) {
            return existing.get();
        }
        final byte[] key = new byte[32];
        RANDOM.nextBytes(key);
        final byte[] nonce = nonce();
        jdbc.sql("""
                        insert into tenancy_tenant_data_key (tenant_id, version, wrapped_key, wrap_nonce, master_key_id)
                        values (:tenantId, 1, :wrapped, :nonce, :masterKeyId)
                        """)
                .param("tenantId", tenantId)
                .param("wrapped", encrypt(requireMasterKey(), nonce, aad(tenantId, "data-key:1"), key))
                .param("nonce", nonce)
                .param("masterKeyId", masterKeyId)
                .update();
        return new DataKey(1, key);
    }

    private byte[] unwrap(final UUID tenantId, final int version, final byte[] wrapped, final byte[] nonce) {
        return decrypt(requireMasterKey(), nonce, aad(tenantId, "data-key:" + version), wrapped);
    }

    private byte[] requireMasterKey() {
        if (masterKey == null) {
            throw new IllegalStateException("T4: ISP_MASTER_KEY is not configured; tenant secrets cannot be used");
        }
        return masterKey;
    }

    private static byte[] aad(final UUID tenantId, final String purpose) {
        return (tenantId + ":" + purpose).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] nonce() {
        final byte[] nonce = new byte[NONCE_BYTES];
        RANDOM.nextBytes(nonce);
        return nonce;
    }

    private static byte[] encrypt(final byte[] key, final byte[] nonce, final byte[] aad, final byte[] plaintext) {
        return gcm(Cipher.ENCRYPT_MODE, key, nonce, aad, plaintext);
    }

    private static byte[] decrypt(final byte[] key, final byte[] nonce, final byte[] aad, final byte[] ciphertext) {
        return gcm(Cipher.DECRYPT_MODE, key, nonce, aad, ciphertext);
    }

    private static byte[] gcm(final int mode, final byte[] key, final byte[] nonce, final byte[] aad, final byte[] input) {
        try {
            final Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            final SecretKey secretKey = new SecretKeySpec(key, "AES");
            cipher.init(mode, secretKey, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad);
            return cipher.doFinal(input);
        } catch (GeneralSecurityException e) {
            // Never include key material or plaintext in the message.
            throw new IllegalStateException("T4: secret encryption failed (" + e.getClass().getSimpleName() + ")", e);
        }
    }

    private static byte[] decode(final String base64) {
        try {
            return Base64.getDecoder().decode(base64.strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("ISP_MASTER_KEY must be base64 of 32 random bytes (see infra/.env.example)");
        }
    }

    private static String fingerprint(final byte[] key) {
        try {
            final byte[] digest = MessageDigest.getInstance("SHA-256").digest(ByteBuffer.wrap(key).array());
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private record DataKey(int version, byte[] key) {}
}
