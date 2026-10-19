package com.corebalt.isp.packages.infrastructure;

import com.corebalt.isp.packages.api.PackageDto;
import com.corebalt.isp.packages.api.PackageRequest;
import com.corebalt.isp.packages.application.PackageService;
import com.corebalt.isp.shared.web.PageRequests;
import com.corebalt.isp.shared.web.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/packages")
@Tag(name = "Packages")
class PackageController {

    private static final Set<String> SORTABLE = Set.of("name", "priceMinor", "createdAt");

    private final PackageService service;

    PackageController(final PackageService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "List the tenant's packages")
    PageResponse<PackageDto> list(
            @RequestParam(required = false) final Boolean active,
            @RequestParam(defaultValue = "0") final int page,
            @RequestParam(defaultValue = "50") final int size,
            @RequestParam(required = false) final String sort) {
        return service.list(active, PageRequests.of(page, size, sort, SORTABLE, Sort.by("name")));
    }

    @PostMapping
    @Operation(summary = "Create a package")
    ResponseEntity<PackageDto> create(@Valid @RequestBody final PackageRequest request) {
        final PackageDto created = service.create(request);
        return ResponseEntity.created(URI.create("/api/v1/packages/" + created.id())).body(created);
    }

    @GetMapping("/{id}")
    @Operation(summary = "One package")
    PackageDto get(@PathVariable final UUID id) {
        return service.get(id);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Revise a package; running cycles keep what they were sold (S6)")
    PackageDto revise(@PathVariable final UUID id, @Valid @RequestBody final PackageRequest request) {
        return service.revise(id, request);
    }

    @PostMapping("/{id}/deactivate")
    @Operation(summary = "Stop selling a package; existing lines keep it")
    PackageDto deactivate(@PathVariable final UUID id) {
        return service.setActive(id, false);
    }

    @PostMapping("/{id}/activate")
    @Operation(summary = "Sell a package again")
    PackageDto activate(@PathVariable final UUID id) {
        return service.setActive(id, true);
    }
}
