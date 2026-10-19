package com.corebalt.isp.identity.application;

import com.corebalt.isp.shared.security.JwtKeys;
import com.corebalt.isp.shared.security.Tokens;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;

/**
 * Signs the platform's tokens with the current key ({@code kid} in the header): access tokens (15
 * minutes) and challenge tokens (5 minutes, one step of a login: password change, TOTP, enrolment,
 * PIN setup). Challenge tokens are accepted by no API (their type is CHALLENGE).
 */
@Component
public class TokenIssuer {

    public static final Duration ACCESS_TTL = Duration.ofMinutes(15);
    public static final Duration CHALLENGE_TTL = Duration.ofMinutes(5);

    /** Who an access token is for. */
    public record Subject(String type, UUID id, UUID tenantId, String role, List<String> permissions, int version) {}

    /** A verified challenge: the subject and the step it allows. */
    public record Challenge(String subjectType, UUID subjectId, UUID tenantId, String purpose) {}

    private final JwtKeys keys;
    private final JwtDecoder decoder;
    private final Clock clock;

    TokenIssuer(final JwtKeys keys, final JwtDecoder decoder, final Clock clock) {
        this.keys = keys;
        this.decoder = decoder;
        this.clock = clock;
    }

    public String access(final Subject subject) {
        return access(subject, ACCESS_TTL, null);
    }

    /** {@code extra}: further claims, such as the real actor ({@code act}) and {@code imp} of an impersonation. */
    public String access(final Subject subject, final Duration ttl, final Map<String, Object> extra) {
        final JWTClaimsSet.Builder claims = base(subject.id(), ttl)
                .claim(Tokens.TYPE, subject.type())
                .claim(Tokens.VERSION, subject.version());
        if (subject.tenantId() != null) {
            claims.claim(Tokens.TENANT, subject.tenantId().toString());
        }
        if (subject.role() != null) {
            claims.claim(Tokens.ROLE, subject.role());
        }
        if (subject.permissions() != null) {
            claims.claim(Tokens.PERMISSIONS, subject.permissions());
        }
        if (extra != null) {
            extra.forEach(claims::claim);
        }
        return sign(claims.build());
    }

    public String challenge(final String subjectType, final UUID subjectId, final UUID tenantId, final String purpose) {
        final JWTClaimsSet.Builder claims = base(subjectId, CHALLENGE_TTL)
                .claim(Tokens.TYPE, Tokens.CHALLENGE)
                .claim("sty", subjectType)
                .claim("purpose", purpose);
        if (tenantId != null) {
            claims.claim(Tokens.TENANT, tenantId.toString());
        }
        return sign(claims.build());
    }

    /** Verifies a challenge for {@code purpose}; anything else (expired, other purpose, an access token) is refused. */
    public Challenge verifyChallenge(final String token, final String purpose) {
        final Jwt jwt;
        try {
            jwt = decoder.decode(token);
        } catch (JwtException e) {
            throw new AuthException("This sign-in step has expired; start again.");
        }
        if (!Tokens.CHALLENGE.equals(jwt.getClaimAsString(Tokens.TYPE)) || !purpose.equals(jwt.getClaimAsString("purpose"))) {
            throw new AuthException("This sign-in step has expired; start again.");
        }
        final String tenant = jwt.getClaimAsString(Tokens.TENANT);
        return new Challenge(jwt.getClaimAsString("sty"), UUID.fromString(jwt.getSubject()),
                tenant == null ? null : UUID.fromString(tenant), purpose);
    }

    private JWTClaimsSet.Builder base(final UUID subject, final Duration ttl) {
        final Instant now = clock.instant();
        return new JWTClaimsSet.Builder()
                .issuer(Tokens.ISSUER)
                .audience(Tokens.AUDIENCE)
                .subject(subject.toString())
                .jwtID(UUID.randomUUID().toString())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(ttl)));
    }

    private String sign(final JWTClaimsSet claims) {
        try {
            final SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(keys.signing().getKeyID()).type(JOSEObjectType.JWT).build(),
                    claims);
            jwt.sign(new RSASSASigner(keys.signing()));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException("token signing failed", e);
        }
    }
}
