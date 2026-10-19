package com.corebalt.isp.network.infrastructure;

import com.corebalt.isp.network.api.NetworkStatistics;
import com.corebalt.isp.network.api.NetworkStatistics.UsageDay;
import com.corebalt.isp.shared.security.CurrentActor;
import com.corebalt.isp.shared.web.InvalidRequestException;
import com.corebalt.isp.shared.web.NotFoundException;
import com.corebalt.isp.subscriptions.api.SubscriptionDirectory;
import com.corebalt.isp.tenancy.api.TenantProfile;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Daily usage of one of the customer's own lines, from network_usage_daily only (N7). A line of
 * another customer of the same tenant is a 404, exactly like a line that does not exist.
 */
@RestController
@Tag(name = "Portal")
class PortalUsageController {

    private static final long MAX_DAYS = 366;

    private final NetworkStatistics statistics;
    private final SubscriptionDirectory lines;
    private final TenantProfile tenantProfile;
    private final Clock clock;

    PortalUsageController(final NetworkStatistics statistics, final SubscriptionDirectory lines,
                          final TenantProfile tenantProfile, final Clock clock) {
        this.statistics = statistics;
        this.lines = lines;
        this.tenantProfile = tenantProfile;
        this.clock = clock;
    }

    @GetMapping("/api/v1/portal/lines/{id}/usage")
    @Operation(summary = "My line's daily usage; tenant-local days, the last 30 by default")
    List<UsageDay> usage(
            @PathVariable final UUID id,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) final LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) final LocalDate to) {
        final UUID customerId = CurrentActor.requireCustomerId();
        lines.line(id).filter(line -> line.customerId().equals(customerId))
                .orElseThrow(() -> new NotFoundException("Subscription"));
        final LocalDate last = to != null ? to : LocalDate.now(clock.withZone(tenantProfile.currentPolicy().zone()));
        final LocalDate first = from != null ? from : last.minusDays(29);
        if (first.isAfter(last) || ChronoUnit.DAYS.between(first, last) >= MAX_DAYS) {
            throw InvalidRequestException.of("from", "from must be on or before to, at most " + MAX_DAYS + " days");
        }
        return statistics.dailyUsage(id, first, last);
    }
}
