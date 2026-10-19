package com.corebalt.isp.network.infrastructure;

import com.corebalt.isp.network.api.RouterDto;
import com.corebalt.isp.network.api.RouterRequest;
import com.corebalt.isp.network.api.RouterSecretDto;
import com.corebalt.isp.network.application.RouterService;
import com.corebalt.isp.shared.web.PageRequests;
import com.corebalt.isp.shared.web.PageResponse;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/routers")
@Tag(name = "Network")
class RouterController {

    private static final Set<String> SORTABLE = Set.of("name", "createdAt");

    private final RouterService service;

    RouterController(final RouterService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "List the tenant's routers")
    PageResponse<RouterDto> list(
            @RequestParam(defaultValue = "0") final int page,
            @RequestParam(defaultValue = "50") final int size,
            @RequestParam(required = false) final String sort) {
        return service.list(PageRequests.of(page, size, sort, SORTABLE, Sort.by("name")));
    }

    @PostMapping
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

    @PostMapping("/{id}/radius-secret")
    @Operation(summary = "Replace the router's RADIUS secret; the new one is shown only this once")
    ResponseEntity<RouterSecretDto> regenerateSecret(@PathVariable final UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.regenerateSecret(id));
    }
}
