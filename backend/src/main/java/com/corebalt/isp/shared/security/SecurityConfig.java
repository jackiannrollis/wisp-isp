package com.corebalt.isp.shared.security;

import com.corebalt.isp.shared.web.ProblemResponseWriter;
import com.corebalt.isp.shared.web.ProblemTypes;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

/**
 * Stateless bearer-token security (ADR 0010). Tokens are the platform's own RS256 JWTs, verified
 * against the keys in {@link JwtKeys} (no network call), with issuer and audience checked. The token
 * type decides the API: STAFF {@code /api/v1/**}, CUSTOMER {@code /api/v1/portal/**}, PLATFORM
 * {@code /api/platform/**}. Login endpoints, the JWKS, health and API docs are public; provider
 * callbacks have their own chain. Everything else is denied. Revoked tokens are refused per request
 * by {@link TokenVersionFilter}.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication
@EnableMethodSecurity
class SecurityConfig {

    /**
     * Provider callbacks (docs/05): no JWT, no CSRF; M-Pesa callbacks admitted only from the allowed
     * networks; the tenant comes from the path token.
     */
    @Bean
    @Order(1)
    SecurityFilterChain callbackSecurity(
            final HttpSecurity http, @Value("${isp.mpesa.callback-allowed-cidrs}") final List<String> allowedCidrs)
            throws Exception {
        http.securityMatcher("/callbacks/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .addFilterBefore(new CallbackSourceFilter(allowedCidrs), AuthorizationFilter.class);
        return http.build();
    }

    /**
     * Logins and token refresh: no bearer token. CSRF is off because nothing here uses an ambient
     * credential except the refresh cookie, which is SameSite=Strict, path-limited and Origin-checked
     * by the refresh endpoint.
     */
    @Bean
    @Order(2)
    SecurityFilterChain authSecurity(final HttpSecurity http) throws Exception {
        http.securityMatcher("/.well-known/jwks.json", "/api/v1/auth/**", "/api/v1/portal-auth/**", "/api/platform/auth/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }

    @Bean
    @Order(3)
    SecurityFilterChain apiSecurity(
            final HttpSecurity http, final ProblemResponseWriter problems, final TokenVersionCheck tokenVersions)
            throws Exception {
        http.csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info", "/api/docs",
                                "/api/docs/**", "/swagger-ui.html", "/swagger-ui/**", "/error")
                        .permitAll()
                        .requestMatchers("/api/platform/**").hasAuthority(Tokens.typeAuthority(Tokens.PLATFORM))
                        .requestMatchers("/api/v1/portal/**").hasAuthority(Tokens.typeAuthority(Tokens.CUSTOMER))
                        .requestMatchers("/api/v1/**").hasAuthority(Tokens.typeAuthority(Tokens.STAFF))
                        .anyRequest().denyAll())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(Permissions::authentication))
                        .authenticationEntryPoint((request, response, e) -> problems.write(request, response,
                                HttpStatus.UNAUTHORIZED, ProblemTypes.UNAUTHENTICATED, "A valid bearer token is required."))
                        .accessDeniedHandler((request, response, e) -> problems.write(request, response,
                                HttpStatus.FORBIDDEN, ProblemTypes.FORBIDDEN, "You do not have permission for this action.")))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, e) -> problems.write(request, response,
                                HttpStatus.UNAUTHORIZED, ProblemTypes.UNAUTHENTICATED, "A valid bearer token is required."))
                        .accessDeniedHandler((request, response, e) -> problems.write(request, response,
                                HttpStatus.FORBIDDEN, ProblemTypes.FORBIDDEN, "You do not have permission for this action.")))
                .addFilterAfter(new TenantContextFilter(problems), AuthorizationFilter.class)
                .addFilterAfter(new TokenVersionFilter(tokenVersions, problems), TenantContextFilter.class);
        return http.build();
    }
}
