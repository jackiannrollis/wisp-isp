package com.corebalt.isp.shared.security;

import com.corebalt.isp.shared.web.ProblemResponseWriter;
import com.corebalt.isp.shared.web.ProblemTypes;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.Order;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Stateless bearer-token security. {@code /api/v1/**} needs an authenticated staff JWT carrying a
 * tenant; health, info and API docs are public; M-Pesa callbacks ({@code /callbacks/**}) get their
 * own IP-restricted chain with the payments module. Everything else is denied.
 */
@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
class SecurityConfig {

    /**
     * Provider callbacks (docs/05): no JWT, no CSRF, admitted only from the allowed networks; the
     * tenant comes from the path token.
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

    @Bean
    @Order(2)
    SecurityFilterChain apiSecurity(final HttpSecurity http, final ProblemResponseWriter problems)
            throws Exception {
        http.csrf(csrf -> csrf.disable())
                .sessionManagement(
                        session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(
                        auth ->
                                auth.requestMatchers(
                                                "/actuator/health",
                                                "/actuator/health/**",
                                                "/actuator/info",
                                                "/api/docs",
                                                "/api/docs/**",
                                                "/swagger-ui.html",
                                                "/swagger-ui/**",
                                                "/error")
                                        .permitAll()
                                        .requestMatchers("/api/v1/**")
                                        .authenticated()
                                        .anyRequest()
                                        .denyAll())
                .oauth2ResourceServer(
                        oauth2 ->
                                oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(Permissions::authentication))
                                        .authenticationEntryPoint(
                                                (request, response, e) ->
                                                        problems.write(
                                                                request,
                                                                response,
                                                                HttpStatus.UNAUTHORIZED,
                                                                ProblemTypes.UNAUTHENTICATED,
                                                                "A valid bearer token is required."))
                                        .accessDeniedHandler(
                                                (request, response, e) ->
                                                        problems.write(
                                                                request,
                                                                response,
                                                                HttpStatus.FORBIDDEN,
                                                                ProblemTypes.FORBIDDEN,
                                                                "You do not have permission for this action.")))
                .exceptionHandling(
                        exceptions ->
                                exceptions
                                        .authenticationEntryPoint(
                                                (request, response, e) ->
                                                        problems.write(
                                                                request,
                                                                response,
                                                                HttpStatus.UNAUTHORIZED,
                                                                ProblemTypes.UNAUTHENTICATED,
                                                                "A valid bearer token is required."))
                                        .accessDeniedHandler(
                                                (request, response, e) ->
                                                        problems.write(
                                                                request,
                                                                response,
                                                                HttpStatus.FORBIDDEN,
                                                                ProblemTypes.FORBIDDEN,
                                                                "You do not have permission for this action.")))
                .addFilterAfter(
                        new TenantContextFilter(problems), AuthorizationFilter.class);
        return http.build();
    }
}
