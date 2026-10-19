package com.corebalt.isp.shared.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * The token decoder (ADR 0010), outside {@link SecurityConfig} because sign-in services verify their
 * own challenge tokens with it in every context, the break-glass command (no web server) included.
 */
@Configuration(proxyBeanMethods = false)
class JwtDecoderConfig {

    /** Verifies with the local key set: RS256 only, the platform's issuer and audience, not expired. */
    @Bean
    JwtDecoder jwtDecoder(final JwtKeys keys) {
        final DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
        processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, new ImmutableJWKSet<>(keys.publicKeys())));
        processor.setJWTClaimsSetVerifier((claims, context) -> {});
        final NimbusJwtDecoder decoder = new NimbusJwtDecoder(processor);
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(Tokens.ISSUER),
                jwt -> jwt.getAudience() != null && jwt.getAudience().contains(Tokens.AUDIENCE)
                        ? OAuth2TokenValidatorResult.success()
                        : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "wrong audience", null))));
        return decoder;
    }
}
