package com.corebalt.isp.shared.security;

import com.corebalt.isp.shared.web.ProblemResponseWriter;
import com.corebalt.isp.shared.web.ProblemTypes;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Refuses an access token whose user has been disabled, changed a password or PIN, or changed role
 * since it was issued (claim {@code ver} older than the user's token version), on every request. Runs
 * after the tenant context is set, so the lookup is tenant-scoped.
 */
public class TokenVersionFilter extends OncePerRequestFilter {

    private final TokenVersionCheck check;
    private final ProblemResponseWriter problems;

    public TokenVersionFilter(final TokenVersionCheck check, final ProblemResponseWriter problems) {
        this.check = check;
        this.problems = problems;
    }

    @Override
    protected void doFilterInternal(
            final HttpServletRequest request, final HttpServletResponse response, final FilterChain chain)
            throws ServletException, IOException {
        if (SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken authentication) {
            final Jwt jwt = authentication.getToken();
            boolean current;
            try {
                current = check.isCurrent(jwt.getClaims());
            } catch (IllegalArgumentException e) {
                current = false;
            }
            if (!current) {
                SecurityContextHolder.clearContext();
                problems.write(request, response, HttpStatus.UNAUTHORIZED, ProblemTypes.UNAUTHENTICATED,
                        "This session has ended; sign in again.");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
