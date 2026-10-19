package com.corebalt.isp.shared.security;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Token signing keys (ADR 0010), from the environment only, never a file in the image:
 * {@code ISP_JWT_KID} and {@code ISP_JWT_PRIVATE_KEY} (base64 PKCS#8 DER, RSA) sign new tokens;
 * {@code ISP_JWT_RETIRED_KEYS} ({@code kid=base64 X.509 DER public key}, comma-separated) are still
 * accepted for verification, so a key can be replaced without logging anyone out. All public keys
 * are published at {@code /.well-known/jwks.json}. The tunnel worker issues no tokens and runs
 * without a key ({@code isp.auth.jwt.required=false}); it then accepts none either.
 */
@Component
public class JwtKeys {

    private final RSAKey signing;
    private final JWKSet publicSet;

    public JwtKeys(
            @Value("${isp.auth.jwt.kid:}") final String kid,
            @Value("${isp.auth.jwt.private-key:}") final String privateKeyBase64,
            @Value("${isp.auth.jwt.retired-keys:}") final String retired,
            @Value("${isp.auth.jwt.required:true}") final boolean required) {
        if (kid.isBlank() || privateKeyBase64.isBlank()) {
            if (required) {
                throw new IllegalStateException("ISP_JWT_KID and ISP_JWT_PRIVATE_KEY must be set (see infra/.env.example)");
            }
            this.signing = null;
            this.publicSet = new JWKSet();
            return;
        }
        try {
            final KeyFactory rsa = KeyFactory.getInstance("RSA");
            final RSAPrivateCrtKey privateKey = (RSAPrivateCrtKey) rsa.generatePrivate(
                    new PKCS8EncodedKeySpec(Base64.getDecoder().decode(privateKeyBase64.strip())));
            final RSAPublicKey publicKey = (RSAPublicKey) rsa.generatePublic(
                    new RSAPublicKeySpec(privateKey.getModulus(), privateKey.getPublicExponent()));
            if (publicKey.getModulus().bitLength() < 2048) {
                throw new IllegalStateException("ISP_JWT_PRIVATE_KEY must be RSA 2048 bits or more");
            }
            this.signing = new RSAKey.Builder(publicKey).privateKey(privateKey).keyID(kid.strip()).keyUse(KeyUse.SIGNATURE).build();
            final List<com.nimbusds.jose.jwk.JWK> keys = new ArrayList<>();
            keys.add(signing.toPublicJWK());
            for (final String entry : retired.split(",")) {
                if (entry.isBlank()) {
                    continue;
                }
                final String[] parts = entry.strip().split("=", 2);
                final RSAPublicKey old = (RSAPublicKey) rsa.generatePublic(
                        new X509EncodedKeySpec(Base64.getDecoder().decode(parts[1].strip())));
                keys.add(new RSAKey.Builder(old).keyID(parts[0].strip()).keyUse(KeyUse.SIGNATURE).build());
            }
            this.publicSet = new JWKSet(keys);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("ISP_JWT_PRIVATE_KEY or ISP_JWT_RETIRED_KEYS is not a valid RSA key ("
                    + e.getClass().getSimpleName() + ")");
        }
    }

    /** The key new tokens are signed with. */
    public RSAKey signing() {
        if (signing == null) {
            throw new IllegalStateException("this process has no token signing key");
        }
        return signing;
    }

    /** Every key a token may be verified with: the signing key and the retired ones. */
    public JWKSet publicKeys() {
        return publicSet;
    }
}
