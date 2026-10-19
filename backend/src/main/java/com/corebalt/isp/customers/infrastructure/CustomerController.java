package com.corebalt.isp.customers.infrastructure;

import com.corebalt.isp.customers.api.CustomerDto;
import com.corebalt.isp.customers.api.CustomerRequest;
import com.corebalt.isp.customers.application.CustomerService;
import com.corebalt.isp.customers.domain.CustomerStatus;
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
@RequestMapping("/api/v1/customers")
@Tag(name = "Customers")
class CustomerController {

    private static final Set<String> SORTABLE = Set.of("number", "name", "createdAt");

    private final CustomerService service;

    CustomerController(final CustomerService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "List customers; q matches name, number or phone")
    PageResponse<CustomerDto> list(
            @RequestParam(required = false) final String q,
            @RequestParam(required = false) final CustomerStatus status,
            @RequestParam(defaultValue = "0") final int page,
            @RequestParam(defaultValue = "50") final int size,
            @RequestParam(required = false) final String sort) {
        return service.list(q, status, PageRequests.of(page, size, sort, SORTABLE, Sort.by("number")));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('customers.write')")
    @Operation(summary = "Register a customer; the number is assigned by the platform (T2)")
    ResponseEntity<CustomerDto> create(@Valid @RequestBody final CustomerRequest request) {
        final CustomerDto created = service.register(request);
        return ResponseEntity.created(URI.create("/api/v1/customers/" + created.id())).body(created);
    }

    @GetMapping("/{id}")
    @Operation(summary = "One customer")
    CustomerDto get(@PathVariable final UUID id) {
        return service.get(id);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('customers.write')")
    @Operation(summary = "Replace a customer's profile; the number never changes")
    CustomerDto update(@PathVariable final UUID id, @Valid @RequestBody final CustomerRequest request) {
        return service.update(id, request);
    }

    @PostMapping("/{id}/archive")
    @PreAuthorize("hasAuthority('customers.write')")
    @Operation(summary = "Archive a customer; the number is never reused")
    CustomerDto archive(@PathVariable final UUID id) {
        return service.archive(id);
    }
}
