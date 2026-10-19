package com.corebalt.isp.shared.security;

import java.util.Optional;
import java.util.UUID;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** The staff member behind the current request, from the JWT subject; empty for jobs and consumers. */
public final class CurrentActor {

    private CurrentActor() {}

    public static boolean isStaff() {
        return SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken;
    }

    /** The staff user ID when the subject is a UUID (identity issues those); otherwise empty. */
    public static Optional<UUID> staffId() {
        if (!(SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken jwt)) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(jwt.getToken().getSubject()).map(UUID::fromString);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
