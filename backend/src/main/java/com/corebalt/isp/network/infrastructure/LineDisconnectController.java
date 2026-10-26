package com.corebalt.isp.network.infrastructure;

import com.corebalt.isp.network.application.StaffDisconnects;
import com.corebalt.isp.shared.web.IdempotentRequests;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** A staff member's "Disconnect sessions" on a line (ADR 0018); a line operation, so `subscriptions.write`. */
@RestController
@Tag(name = "Network")
class LineDisconnectController {

    record DisconnectRequest(@NotBlank @Size(max = 500) String reason) {}

    record DisconnectResult(UUID subscriptionId, int sessions) {}

    private final StaffDisconnects disconnects;
    private final IdempotentRequests idempotent;

    LineDisconnectController(final StaffDisconnects disconnects, final IdempotentRequests idempotent) {
        this.disconnects = disconnects;
        this.idempotent = idempotent;
    }

    @PostMapping("/api/v1/subscriptions/{id}/disconnect")
    @PreAuthorize("hasAuthority('subscriptions.write')")
    @Operation(summary = "Disconnect every live session of the line so it redials, with a reason (audited; 409 when it has "
            + "none). Within 2 hours after a payment it counts as a manual reconnect for Gate 1. Needs an Idempotency-Key")
    ResponseEntity<DisconnectResult> disconnect(
            @RequestHeader(IdempotentRequests.HEADER) final String key,
            @PathVariable final UUID id,
            @Valid @RequestBody final DisconnectRequest request) {
        final IdempotentRequests.Result<DisconnectResult> result = idempotent.execute(
                key, "POST /subscriptions/" + id + "/disconnect", request, DisconnectResult.class, () -> {
                    final StaffDisconnects.Result done = disconnects.disconnect(id, request.reason().strip());
                    return new DisconnectResult(done.subscriptionId(), done.sessions());
                });
        final ResponseEntity.BodyBuilder response = ResponseEntity.ok();
        if (result.replayed()) {
            response.header(IdempotentRequests.REPLAYED_HEADER, "true");
        }
        return response.body(result.body());
    }
}
