package com.corebalt.isp.identity.infrastructure;

import com.corebalt.isp.identity.application.AuthException;
import com.corebalt.isp.identity.application.RefreshTokens;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * The refresh-token cookie (ADR 0010): httpOnly, Secure, SameSite=Strict, and sent only to its
 * session endpoints ({@code Path}), never to the rest of the API. Endpoints that read it first check
 * that the request's {@code Origin} is one of the platform's own front ends.
 */
@Component
class SessionCookies {

    static final String NAME = "isp_rt";
    static final String STAFF_PATH_SUFFIX = "/session";
    static final String PORTAL_PATH_SUFFIX = "/session";
    static final String PLATFORM_PATH_SUFFIX = "/session";
    static final String STAFF_PATH = "/api/v1/auth" + STAFF_PATH_SUFFIX;
    static final String PORTAL_PATH = "/api/v1/portal-auth" + PORTAL_PATH_SUFFIX;
    static final String PLATFORM_PATH = "/api/platform/auth" + PLATFORM_PATH_SUFFIX;

    private final List<String> allowedOrigins;

    SessionCookies(@Value("${isp.auth.allowed-origins}") final List<String> allowedOrigins) {
        this.allowedOrigins = allowedOrigins.stream().map(String::strip).filter(o -> !o.isEmpty()).toList();
    }

    String set(final String path, final RefreshTokens.Issued issued) {
        return cookie(path, issued.token(), issued.ttl()).toString();
    }

    String clear(final String path) {
        return cookie(path, "", Duration.ZERO).toString();
    }

    /** The cookie's token after the Origin check; a missing or foreign Origin is refused. */
    Optional<String> read(final HttpServletRequest request) {
        final String origin = request.getHeader(HttpHeaders.ORIGIN);
        if (origin == null || !allowed(origin)) {
            throw new AuthException("This request did not come from a Corebalt page.");
        }
        return Optional.ofNullable(request.getCookies()).stream().flatMap(Arrays::stream)
                .filter(c -> NAME.equals(c.getName()))
                .map(Cookie::getValue)
                .filter(v -> !v.isBlank())
                .findFirst();
    }

    /**
     * An exact origin, or a wildcard entry {@code https://*.example.com} for the tenant portals on their
     * subdomains (docs/07): it matches exactly one DNS label in place of the star, never a deeper name,
     * the bare domain or another scheme.
     */
    boolean allowed(final String origin) {
        for (final String entry : allowedOrigins) {
            if (entry.equals(origin)) {
                return true;
            }
            final int star = entry.indexOf("://*.");
            if (star > 0) {
                final String prefix = entry.substring(0, star + 3);
                final String suffix = entry.substring(star + 4);
                if (origin.startsWith(prefix) && origin.endsWith(suffix)) {
                    final String label = origin.substring(prefix.length(), origin.length() - suffix.length());
                    if (LABEL.matcher(label).matches()) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static final java.util.regex.Pattern LABEL = java.util.regex.Pattern.compile("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?");

    private static ResponseCookie cookie(final String path, final String value, final Duration maxAge) {
        return ResponseCookie.from(NAME, value)
                .httpOnly(true)
                .secure(true)
                .sameSite("Strict")
                .path(path)
                .maxAge(maxAge)
                .build();
    }
}
