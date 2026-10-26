package com.corebalt.isp.reporting.infrastructure;

import com.corebalt.isp.reporting.api.Gate1Dto;
import com.corebalt.isp.reporting.application.Gate1Service;
import com.corebalt.isp.shared.tenant.TenantContext;
import com.corebalt.isp.shared.web.InvalidRequestException;
import com.corebalt.isp.shared.web.NotFoundException;
import com.corebalt.isp.tenancy.api.TenantRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Gate 1 of a tenant for the platform console (ADR 0018); PLATFORM tokens only, like all of /api/platform. */
@RestController
@Tag(name = "Platform")
class Gate1Controller {

    static final int MAX_DAYS = 90;

    private final Gate1Service gate1;
    private final TenantRegistry tenants;
    private final Clock clock;

    Gate1Controller(final Gate1Service gate1, final TenantRegistry tenants, final Clock clock) {
        this.gate1 = gate1;
        this.tenants = tenants;
        this.clock = clock;
    }

    @GetMapping("/api/platform/tenants/{tenantId}/gate1")
    @Operation(summary = "Gate 1 over the last days: payment matching, restore times and reconnects needing hands")
    Gate1Dto gate1(@PathVariable final UUID tenantId, @RequestParam(defaultValue = "30") final int days) {
        if (days < 1 || days > MAX_DAYS) {
            throw InvalidRequestException.of("days", "must be between 1 and " + MAX_DAYS);
        }
        tenants.find(tenantId).orElseThrow(() -> new NotFoundException("Tenant"));
        final Instant to = clock.instant();
        return TenantContext.callAsTenant(tenantId, () -> gate1.gate1(to.minus(Duration.ofDays(days)), to));
    }
}
