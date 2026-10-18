package com.corebalt.isp.shared.security;

import com.nimbusds.jwt.JWT;
import com.nimbusds.jwt.JWTParser;
import java.text.ParseException;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/**
 * STUB: decodes JWTs WITHOUT verifying the signature. Only active under the {@code local} and
 * {@code test} profiles; any other profile has no {@link JwtDecoder} and the application refuses to
 * start until real token validation (identity module, docs/08) is configured. Expiry is still
 * checked so tests can exercise it.
 */
@Configuration(proxyBeanMethods = false)
@Profile({"local", "test"})
class StubJwtDecoderConfig {

    private static final Logger log = LoggerFactory.getLogger(StubJwtDecoderConfig.class);

    @Bean
    JwtDecoder stubJwtDecoder() {
        log.warn("Using the STUB JwtDecoder: token signatures are NOT verified (local/test only)");
        return StubJwtDecoderConfig::decode;
    }

    static Jwt decode(final String token) {
        final JWT parsed;
        final Map<String, Object> claims;
        try {
            parsed = JWTParser.parse(token);
            claims = new LinkedHashMap<>(parsed.getJWTClaimsSet().getClaims());
        } catch (ParseException e) {
            throw new BadJwtException("Malformed token", e);
        }
        claims.replaceAll((name, value) -> value instanceof Date date ? date.toInstant() : value);
        final Instant issuedAt =
                claims.get("iat") instanceof Instant iat ? iat : Instant.EPOCH;
        final Instant expiresAt =
                claims.get("exp") instanceof Instant exp ? exp : null;
        if (expiresAt != null && expiresAt.isBefore(Instant.now())) {
            throw new BadJwtException("Token expired");
        }
        if (claims.isEmpty()) {
            throw new BadJwtException("Token has no claims");
        }
        return new Jwt(
                token,
                issuedAt,
                expiresAt == null ? issuedAt.plusSeconds(1) : expiresAt,
                Map.copyOf(parsed.getHeader().toJSONObject()),
                claims);
    }
}
