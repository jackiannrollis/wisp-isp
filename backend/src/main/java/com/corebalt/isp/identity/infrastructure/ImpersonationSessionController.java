package com.corebalt.isp.identity.infrastructure;

import com.corebalt.isp.identity.application.Impersonations;
import com.corebalt.isp.shared.security.CurrentActor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** The impersonated session's End button (ADR 0017): it ends the impersonation its own token names. */
@RestController
@Tag(name = "Sign-in")
class ImpersonationSessionController {

    private final Impersonations impersonations;

    ImpersonationSessionController(final Impersonations impersonations) {
        this.impersonations = impersonations;
    }

    @PostMapping("/api/v1/impersonation/end")
    @Operation(summary = "End the impersonation this token belongs to; the token stops working at once")
    Impersonations.ImpersonationDto end() {
        final UUID id = CurrentActor.impersonationId()
                .orElseThrow(() -> new AccessDeniedException("an impersonation token is required"));
        final UUID admin = CurrentActor.actingPlatformAdmin()
                .orElseThrow(() -> new AccessDeniedException("an impersonation token is required"));
        return impersonations.endFromSession(id, admin);
    }
}
