package com.corebalt.isp.shared.security;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Who is behind the current request, from the access token (ADR 0010); empty for jobs and
 * consumers. During an impersonation the token is the staff member's, and {@link #actingPlatformAdmin}
 * names the platform admin really acting ({@code act.sub}).
 */
public final class CurrentActor {

    private CurrentActor() {}

    public static Optional<Jwt> token() {
        return SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken jwt
                ? Optional.of(jwt.getToken())
                : Optional.empty();
    }

    public static boolean isStaff() {
        return token().map(t -> Tokens.STAFF.equals(t.getClaimAsString(Tokens.TYPE))).orElse(false);
    }

    /** The staff user of a STAFF token (also during an impersonation); otherwise empty. */
    public static Optional<UUID> staffId() {
        return token().filter(t -> Tokens.STAFF.equals(t.getClaimAsString(Tokens.TYPE))).flatMap(CurrentActor::subject);
    }

    /** The customer of a CUSTOMER (portal) token; otherwise empty. */
    public static Optional<UUID> customerId() {
        return token().filter(t -> Tokens.CUSTOMER.equals(t.getClaimAsString(Tokens.TYPE))).flatMap(CurrentActor::subject);
    }

    /**
     * The portal's customer. Portal endpoints take the customer only from here, never from the request:
     * row-level security separates tenants, not customers of one tenant.
     */
    public static UUID requireCustomerId() {
        return customerId().orElseThrow(() -> new AccessDeniedException("a customer token is required"));
    }

    /** Whether the current caller was granted {@code permission} (a code from {@link Permissions}). */
    public static boolean has(final String permission) {
        final var authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null
                && authentication.getAuthorities().stream().anyMatch(a -> permission.equals(a.getAuthority()));
    }

    /** The platform admin of a PLATFORM token; otherwise empty. */
    public static Optional<UUID> platformAdminId() {
        return token().filter(t -> Tokens.PLATFORM.equals(t.getClaimAsString(Tokens.TYPE))).flatMap(CurrentActor::subject);
    }

    /**
     * ADR 0017: secrets stay with the tenant, so an operation that sets or reveals one (a PPPoE password, a
     * router's RADIUS secret, onboarding script or REST login) is refused while Corebalt support impersonates,
     * even under a permission the impersonated role holds. 403, like a missing permission.
     */
    public static void requireNotImpersonating(final String what) {
        if (impersonating()) {
            throw new AccessDeniedException(what + " is not allowed while impersonating (ADR 0017)");
        }
    }

    /** Whether Corebalt support is behind the current request (ADR 0017). */
    public static boolean impersonating() {
        return actingPlatformAdmin().isPresent();
    }

    /** The impersonation an impersonation token belongs to ({@code imp}); otherwise empty. */
    public static Optional<UUID> impersonationId() {
        return token().map(t -> t.getClaimAsString("imp")).flatMap(CurrentActor::parse);
    }

    /** The platform admin behind an impersonation token ({@code act.sub}); otherwise empty. */
    public static Optional<UUID> actingPlatformAdmin() {
        return token().map(t -> t.getClaims().get(Tokens.ACTOR))
                .filter(Map.class::isInstance)
                .map(actor -> ((Map<?, ?>) actor).get("sub"))
                .flatMap(sub -> parse(sub.toString()));
    }

    private static Optional<UUID> subject(final Jwt jwt) {
        return parse(jwt.getSubject());
    }

    private static Optional<UUID> parse(final String value) {
        try {
            return Optional.ofNullable(value).map(UUID::fromString);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
