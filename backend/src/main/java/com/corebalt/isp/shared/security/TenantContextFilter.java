package com.corebalt.isp.shared.security;

import com.corebalt.isp.shared.tenant.TenantContext;
import com.corebalt.isp.shared.tenant.TenantScope;
import com.corebalt.isp.shared.web.ProblemResponseWriter;
import com.corebalt.isp.shared.web.ProblemTypes;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Resolves the tenant context once per request from the {@code tenant_id} claim of the staff JWT
 * (docs/07). Runs after authorization on {@code /api/v1/**}; a request without a
 * valid tenant claim is refused with a problem detail (T1). Portal (subdomain) and M-Pesa callback
 * (path token) resolution are added with those features.
 */
public class TenantContextFilter extends OncePerRequestFilter {

    public static final String TENANT_CLAIM = "tenant_id";

    private final ProblemResponseWriter problemWriter;

    public TenantContextFilter(final ProblemResponseWriter problemWriter) {
        this.problemWriter = problemWriter;
    }

    @Override
    protected boolean shouldNotFilter(final HttpServletRequest request) {
        return !request.getRequestURI().startsWith(request.getContextPath() + "/api/v1/");
    }

    @Override
    protected void doFilterInternal(
            final HttpServletRequest request,
            final HttpServletResponse response,
            final FilterChain chain)
            throws ServletException, IOException {
        final Optional<UUID> tenantId =
                tenantIdFrom(SecurityContextHolder.getContext().getAuthentication());
        if (tenantId.isEmpty()) {
            problemWriter.write(
                    request,
                    response,
                    HttpStatus.FORBIDDEN,
                    ProblemTypes.TENANT_CONTEXT_MISSING,
                    "The access token carries no valid tenant_id claim.");
            return;
        }
        TenantContext.set(new TenantScope.Tenant(tenantId.get()));
        try {
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }

    static Optional<UUID> tenantIdFrom(final Authentication authentication) {
        if (!(authentication instanceof JwtAuthenticationToken jwt)) {
            return Optional.empty();
        }
        final String claim = jwt.getToken().getClaimAsString(TENANT_CLAIM);
        if (claim == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(claim));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
