package com.corebalt.isp.subscriptions.infrastructure;

import com.corebalt.isp.shared.web.PageRequests;
import com.corebalt.isp.shared.web.PageResponse;
import com.corebalt.isp.subscriptions.api.CreateLineRequest;
import com.corebalt.isp.subscriptions.api.LineOpenedDto;
import com.corebalt.isp.subscriptions.api.PppoeCredentialDto;
import com.corebalt.isp.subscriptions.api.SubscriptionDto;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/subscriptions")
@Tag(name = "Subscriptions")
class SubscriptionController {

    private static final Set<String> SORTABLE = Set.of("pppoeUsername", "createdAt");

    private final LineService service;

    SubscriptionController(final LineService service) {
        this.service = service;
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
}
