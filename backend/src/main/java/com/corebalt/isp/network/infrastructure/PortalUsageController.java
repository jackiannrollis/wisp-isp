package com.corebalt.isp.network.infrastructure;

import com.corebalt.isp.network.api.NetworkStatistics;
import com.corebalt.isp.network.api.NetworkStatistics.UsageDay;
import com.corebalt.isp.network.application.SessionViews;
import com.corebalt.isp.shared.security.CurrentActor;
import com.corebalt.isp.shared.web.InvalidRequestException;
import com.corebalt.isp.shared.web.NotFoundException;
import com.corebalt.isp.subscriptions.api.SubscriptionDirectory;
import com.corebalt.isp.tenancy.api.TenantProfile;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Clock;
import java.time.Instant;
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
    private final SessionViews sessions;
    private final SubscriptionDirectory lines;
    private final TenantProfile tenantProfile;
    private final Clock clock;

    PortalUsageController(final NetworkStatistics statistics, final SessionViews sessions, final SubscriptionDirectory lines,
                          final TenantProfile tenantProfile, final Clock clock) {
        this.statistics = statistics;
        this.sessions = sessions;
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
        requireOwnLine(id);
        final LocalDate last = to != null ? to : LocalDate.now(clock.withZone(tenantProfile.currentPolicy().zone()));
        final LocalDate first = from != null ? from : last.minusDays(29);
        if (first.isAfter(last) || ChronoUnit.DAYS.between(first, last) >= MAX_DAYS) {
            throw InvalidRequestException.of("from", "from must be on or before to, at most " + MAX_DAYS + " days");
        }
        return statistics.dailyUsage(id, first, last);
    }

    /**
     * The pay page polls this after a payment (docs/06 Restricted mode): {@code restored} once the line is
     * FULL and its live session got the new profile. The page shows the "restart your router" fallback
     * itself after 90 seconds.
     */
    @GetMapping("/api/v1/portal/lines/{id}/restore-status")
    @Operation(summary = "Whether my line's access is restored since a payment (since = when the payment was made)")
    SessionViews.RestoreStatus restoreStatus(@PathVariable final UUID id, @RequestParam final Instant since) {
        requireOwnLine(id);
        final String profile = lines.accessProfile(id, clock.instant(), null).orElse("REJECT");
        return sessions.restoreStatus(id, profile, since);
    }

    /** Another customer's line is a 404, exactly like one that does not exist (T1). */
    private void requireOwnLine(final UUID id) {
        final UUID customerId = CurrentActor.requireCustomerId();
        lines.line(id).filter(line -> line.customerId().equals(customerId))
                .orElseThrow(() -> new NotFoundException("Subscription"));
    }
}
