package com.corebalt.isp.support;

import com.corebalt.isp.shared.security.Tokens;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A throwaway RS256 key pair generated per test JVM (never a fixture on disk) and its kid; tests
 * sign tokens with it exactly as {@code TokenIssuer} does (ADR 0010).
 */
public final class TestTokens {

    public static final String KID = "test-key";
    private static final KeyPair KEYS = generate();

    private TestTokens() {}

    public static String privateKeyBase64() {
        return Base64.getEncoder().encodeToString(KEYS.getPrivate().getEncoded());
    }

    /** Valid issuer, audience, random subject and a one-hour expiry, overridden by {@code claims}. */
    public static String sign(final Map<String, Object> claims) {
        return sign(KEYS, claims);
    }

    /** Signed with another key under the same kid: a token the platform must refuse. */
    public static String signWithForeignKey(final Map<String, Object> claims) {
        return sign(generate(), claims);
    }

    /** A STAFF access token at token version 0. */
    public static String staff(final UUID staffId, final UUID tenantId, final String... permissions) {
        return sign(Map.of("sub", staffId.toString(), Tokens.TYPE, Tokens.STAFF, Tokens.TENANT, tenantId.toString(),
                Tokens.PERMISSIONS, List.of(permissions), Tokens.VERSION, 0));
    }

    /** A CUSTOMER (portal) access token at token version 0; the customer needs an identity_customer_credential row. */
    public static String customer(final UUID customerId, final UUID tenantId) {
        return sign(Map.of("sub", customerId.toString(), Tokens.TYPE, Tokens.CUSTOMER, Tokens.TENANT, tenantId.toString(),
                Tokens.VERSION, 0));
    }

    private static String sign(final KeyPair keys, final Map<String, Object> claims) {
        final Instant now = Instant.now();
        final JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder()
                .issuer(Tokens.ISSUER)
                .audience(Tokens.AUDIENCE)
                .subject(UUID.randomUUID().toString())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(Duration.ofHours(1))));
        claims.forEach((name, value) -> {
            switch (name) {
                case "exp" -> builder.expirationTime(Date.from(Instant.ofEpochSecond(((Number) value).longValue())));
                case "aud" -> builder.audience(value.toString());
                case "iss" -> builder.issuer(value.toString());
                case "sub" -> builder.subject(value.toString());
                default -> builder.claim(name, value);
            }
        });
        try {
            final SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KID).build(), builder.build());
            jwt.sign(new RSASSASigner(keys.getPrivate()));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    private static KeyPair generate() {
        try {
            final KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
