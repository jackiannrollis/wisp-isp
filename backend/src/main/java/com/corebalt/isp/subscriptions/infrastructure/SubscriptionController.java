package com.corebalt.isp.subscriptions.infrastructure;

import com.corebalt.isp.shared.web.IdempotentRequests;
import com.corebalt.isp.shared.web.PageRequests;
import com.corebalt.isp.shared.web.PageResponse;
import com.corebalt.isp.subscriptions.api.CreateLineRequest;
import com.corebalt.isp.subscriptions.api.LineOpenedDto;
import com.corebalt.isp.subscriptions.api.PauseRequest;
import com.corebalt.isp.subscriptions.api.PppoeCredentialDto;
import com.corebalt.isp.subscriptions.api.ReasonRequest;
import com.corebalt.isp.subscriptions.api.SubscriptionDto;
import com.corebalt.isp.subscriptions.application.LifecycleService;
import com.corebalt.isp.subscriptions.application.LineService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/subscriptions")
@Tag(name = "Subscriptions")
class SubscriptionController {

    private static final Set<String> SORTABLE = Set.of("pppoeUsername", "createdAt");

    private final LineService service;
    private final LifecycleService lifecycle;
    private final IdempotentRequests idempotent;

    SubscriptionController(
            final LineService service, final LifecycleService lifecycle, final IdempotentRequests idempotent) {
        this.service = service;
        this.lifecycle = lifecycle;
        this.idempotent = idempotent;
    }

    @GetMapping
    @Operation(summary = "List lines, optionally of one customer")
    PageResponse<SubscriptionDto> list(
            @RequestParam(required = false) final UUID customerId,
            @RequestParam(defaultValue = "0") final int page,
            @RequestParam(defaultValue = "50") final int size,
            @RequestParam(required = false) final String sort) {
        return service.list(customerId, PageRequests.of(page, size, sort, SORTABLE, Sort.by("pppoeUsername")));
    }

    @PostMapping
    @Operation(summary = "Open a PENDING PPPoE line; the password in the response is shown only this once")
    ResponseEntity<LineOpenedDto> open(@Valid @RequestBody final CreateLineRequest request) {
        final LineOpenedDto opened = service.open(request);
        return ResponseEntity.created(URI.create("/api/v1/subscriptions/" + opened.subscription().id()))
                .cacheControl(CacheControl.noStore())
                .body(opened);
    }

    @GetMapping("/{id}")
    @Operation(summary = "One line (never its password)")
    SubscriptionDto get(@PathVariable final UUID id) {
        return service.get(id);
    }

    @PostMapping("/{id}/pppoe-password")
    @Operation(summary = "Replace the PPPoE password; the new one is shown only this once. There is no way to reveal a password")
    ResponseEntity<PppoeCredentialDto> regeneratePassword(@PathVariable final UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.regeneratePassword(id));
    }

    @PostMapping("/{id}/pause")
    @Operation(summary = "Pause an ACTIVE line with time left (S7); RESTRICTED until resumed or pause_until")
    ResponseEntity<SubscriptionDto> pause(
            @RequestHeader(IdempotentRequests.HEADER) final String key,
            @PathVariable final UUID id,
            @Valid @RequestBody final PauseRequest request) {
        return respond(idempotent.execute(
                key, "POST /subscriptions/" + id + "/pause", request, SubscriptionDto.class,
                () -> lifecycle.pause(id, request.pauseUntil(), null)));
    }

    @PostMapping("/{id}/resume")
    @Operation(summary = "Resume a PAUSED line; the paused time is added to its expiry (S7)")
    ResponseEntity<SubscriptionDto> resume(
            @RequestHeader(IdempotentRequests.HEADER) final String key, @PathVariable final UUID id) {
        return respond(idempotent.execute(
                key, "POST /subscriptions/" + id + "/resume", null, SubscriptionDto.class,
                () -> lifecycle.resume(id, null)));
    }

    @PostMapping("/{id}/suspend")
    @Operation(summary = "Admin hold with a reason: Access-Reject until lifted")
    ResponseEntity<SubscriptionDto> suspend(
            @RequestHeader(IdempotentRequests.HEADER) final String key,
            @PathVariable final UUID id,
            @Valid @RequestBody final ReasonRequest request) {
        return respond(idempotent.execute(
                key, "POST /subscriptions/" + id + "/suspend", request, SubscriptionDto.class,
                () -> lifecycle.suspend(id, request.reason())));
    }

    @PostMapping("/{id}/lift-suspension")
    @Operation(summary = "Lift a hold; dates are re-evaluated, so an expired line returns RESTRICTED (S8)")
    ResponseEntity<SubscriptionDto> liftSuspension(
            @RequestHeader(IdempotentRequests.HEADER) final String key,
            @PathVariable final UUID id,
            @Valid @RequestBody final ReasonRequest request) {
        return respond(idempotent.execute(
                key, "POST /subscriptions/" + id + "/lift-suspension", request, SubscriptionDto.class,
                () -> lifecycle.liftSuspension(id, request.reason())));
    }

    @PostMapping("/{id}/terminate")
    @Operation(summary = "Close the line: its password is revoked and its static IP freed")
    ResponseEntity<SubscriptionDto> terminate(
            @RequestHeader(IdempotentRequests.HEADER) final String key,
            @PathVariable final UUID id,
            @Valid @RequestBody final ReasonRequest request) {
        return respond(idempotent.execute(
                key, "POST /subscriptions/" + id + "/terminate", request, SubscriptionDto.class,
                () -> lifecycle.terminate(id, request.reason())));
    }

    private static ResponseEntity<SubscriptionDto> respond(final IdempotentRequests.Result<SubscriptionDto> result) {
        final ResponseEntity.BodyBuilder response = ResponseEntity.ok();
        if (result.replayed()) {
            response.header(IdempotentRequests.REPLAYED_HEADER, "true");
        }
        return response.body(result.body());
    }
}
