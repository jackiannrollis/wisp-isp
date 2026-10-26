package com.corebalt.isp.network.infrastructure;

import com.corebalt.isp.network.api.RouterDto;
import com.corebalt.isp.network.api.RouterSessionsDto;
import com.corebalt.isp.network.application.SessionViews;
import com.corebalt.isp.network.api.RouterRequest;
import com.corebalt.isp.network.api.RouterOnboardingRequest;
import com.corebalt.isp.network.api.RouterOnboardingScriptDto;
import com.corebalt.isp.network.api.RouterRetireRequest;
import com.corebalt.isp.network.api.RouterSecretDto;
import com.corebalt.isp.network.application.RouterOnboarding;
import com.corebalt.isp.network.application.RouterRetirement;
import com.corebalt.isp.network.application.RouterService;
import com.corebalt.isp.shared.web.IdempotentRequests;
import com.corebalt.isp.shared.web.PageRequests;
import com.corebalt.isp.shared.web.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
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
@RequestMapping("/api/v1/routers")
@Tag(name = "Network")
class RouterController {

    private static final Set<String> SORTABLE = Set.of("name", "createdAt");

    private final RouterService service;
    private final SessionViews sessions;
    private final RouterOnboarding onboarding;
    private final RouterRetirement retirement;
    private final IdempotentRequests idempotent;

    RouterController(final RouterService service, final SessionViews sessions, final RouterOnboarding onboarding,
                     final RouterRetirement retirement, final IdempotentRequests idempotent) {
        this.service = service;
        this.sessions = sessions;
        this.onboarding = onboarding;
        this.retirement = retirement;
        this.idempotent = idempotent;
    }

    @GetMapping("/{id}/sessions")
    @Operation(summary = "Open sessions in radacct beside the router's own /ppp/active list as last polled, with its age")
    RouterSessionsDto sessions(@PathVariable final UUID id) {
        return sessions.routerSessions(id);
    }

    @GetMapping
    @Operation(summary = "List the tenant's routers: the active ones, or every one with includeRetired (N10)")
    PageResponse<RouterDto> list(
            @RequestParam(defaultValue = "0") final int page,
            @RequestParam(defaultValue = "50") final int size,
            @RequestParam(required = false) final String sort,
            @RequestParam(defaultValue = "false") final boolean includeRetired) {
        return service.list(PageRequests.of(page, size, sort, SORTABLE, Sort.by("name")), includeRetired);
    }

    @PostMapping("/{id}/retire")
    @PreAuthorize("hasAuthority('network.write')")
    @Operation(summary = "Retire a router for good (N10, ADR 0015): its lines move to replacementRouterId (required while it has "
            + "lines), its RADIUS secret and REST login go, its open sessions close; the host's sync removes its WireGuard "
            + "peer within a minute. Needs an Idempotency-Key")
    ResponseEntity<RouterDto> retire(
            @RequestHeader(IdempotentRequests.HEADER) final String key,
            @PathVariable final UUID id,
            @Valid @RequestBody final RouterRetireRequest request) {
        final IdempotentRequests.Result<RouterDto> result = idempotent.execute(
                key, "POST /routers/" + id + "/retire", request, RouterDto.class, () -> retirement.retire(id, request));
        final ResponseEntity.BodyBuilder response = ResponseEntity.ok();
        if (result.replayed()) {
            response.header(IdempotentRequests.REPLAYED_HEADER, "true");
        }
        return response.body(result.body());
    }

    @PostMapping
    @PreAuthorize("hasAuthority('network.write')")
    @Operation(summary = "Register a router; the RADIUS secret in the response is shown only this once")
    ResponseEntity<RouterSecretDto> register(@Valid @RequestBody final RouterRequest request) {
        final RouterSecretDto created = service.register(request);
        return ResponseEntity.created(URI.create("/api/v1/routers/" + created.router().id()))
                .cacheControl(CacheControl.noStore())
                .body(created);
    }

    @GetMapping("/{id}")
    @Operation(summary = "One router (never its secret)")
    RouterDto get(@PathVariable final UUID id) {
        return service.get(id);
    }

    record RestCredential(@jakarta.validation.constraints.NotBlank String username,
                          @jakarta.validation.constraints.NotBlank String password) {}

    @org.springframework.web.bind.annotation.PutMapping("/{id}/rest-credential")
    @PreAuthorize("hasAuthority('network.write')")
    @Operation(summary = "Set the RouterOS REST login the tunnel worker polls with; it can never be read back (T4)")
    RouterDto setRestCredential(@PathVariable final UUID id, @Valid @RequestBody final RestCredential request) {
        return service.setRestCredential(id, request.username(), request.password());
    }

    @org.springframework.web.bind.annotation.DeleteMapping("/{id}/rest-credential")
    @PreAuthorize("hasAuthority('network.write')")
    @Operation(summary = "Stop monitoring the router")
    RouterDto removeRestCredential(@PathVariable final UUID id) {
        return service.removeRestCredential(id);
    }

    @PostMapping("/{id}/poll")
    @PreAuthorize("hasAuthority('network.write')")
    @Operation(summary = "Ask the tunnel worker to poll the router now (RouterPollRequested)")
    ResponseEntity<Void> poll(@PathVariable final UUID id) {
        service.requestPoll(id);
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/{id}/onboarding-script")
    @PreAuthorize("hasAuthority('network.write')")
    @Operation(summary = "Generate the router's onboarding script (docs/06), shown only this once: it carries a new WireGuard "
            + "private key, a new RADIUS secret (FreeRADIUS loads it at its next reload) and a new REST password, which the "
            + "platform keeps for monitoring; the previous ones stop working once the router runs the script")
    ResponseEntity<RouterOnboardingScriptDto> onboardingScript(
            @PathVariable final UUID id, @Valid @RequestBody final RouterOnboardingRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(onboarding.generate(id, request));
    }

    @PostMapping("/{id}/radius-secret")
    @PreAuthorize("hasAuthority('network.write')")
    @Operation(summary = "Replace the router's RADIUS secret; the new one is shown only this once")
    ResponseEntity<RouterSecretDto> regenerateSecret(@PathVariable final UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.regenerateSecret(id));
    }
}
