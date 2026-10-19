package com.corebalt.isp.reporting.application;

import com.corebalt.isp.network.api.NetworkStatistics;
import com.corebalt.isp.payments.api.PaymentTotals;
import com.corebalt.isp.reporting.api.DashboardDto;
import com.corebalt.isp.shared.money.MoneyDto;
import com.corebalt.isp.shared.web.InvalidRequestException;
import com.corebalt.isp.subscriptions.api.LineStatistics;
import com.corebalt.isp.tenancy.api.TenantProfile;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Composes the dashboard from the owning modules' read interfaces; reporting reads no other module's
 * tables (docs/02). Day boundaries are the tenant's time zone (conventions.md, Time).
 */
@Service
public class DashboardService {

    static final long MAX_DAYS = 366;

    private final LineStatistics lines;
    private final PaymentTotals payments;
    private final NetworkStatistics network;
    private final TenantProfile tenantProfile;
    private final Clock clock;

    DashboardService(final LineStatistics lines, final PaymentTotals payments, final NetworkStatistics network,
                     final TenantProfile tenantProfile, final Clock clock) {
        this.lines = lines;
        this.payments = payments;
        this.network = network;
        this.tenantProfile = tenantProfile;
        this.clock = clock;
    }

    /** {@code from} and {@code to} default to the first and last day of the current tenant-local month. */
    @Transactional(readOnly = true)
    public DashboardDto dashboard(final LocalDate from, final LocalDate to) {
        final ZoneId zone = tenantProfile.currentPolicy().zone();
        final Instant now = clock.instant();
        final LocalDate today = LocalDate.ofInstant(now, zone);
        final LocalDate first = from != null ? from : today.withDayOfMonth(1);
        final LocalDate last = to != null ? to : first.with(TemporalAdjusters.lastDayOfMonth());
        if (first.isAfter(last) || ChronoUnit.DAYS.between(first, last) >= MAX_DAYS) {
            throw InvalidRequestException.of("from", "from must be on or before to, at most " + MAX_DAYS + " days");
        }
        final Instant start = first.atStartOfDay(zone).toInstant();
        final Instant end = last.plusDays(1).atStartOfDay(zone).toInstant();

        final LineStatistics.LineCounts counts = lines.counts(now);
        final LineStatistics.RenewalsDue due = lines.renewalsDue(start, end);
        final PaymentTotals.Collected collected = payments.collected(start, end);
        final NetworkStatistics.UsageTotals usage = network.usage(first, last);
        return new DashboardDto(
                first,
                last,
                zone.getId(),
                now,
                new DashboardDto.Lines(counts.total(), counts.byState(), counts.byDisplayStatus()),
                new DashboardDto.Revenue(due.lines(), MoneyDto.of(due.amount()), collected.payments(),
                        MoneyDto.of(collected.amount()), collected.unmatchedPayments(), MoneyDto.of(collected.unmatchedAmount())),
                new DashboardDto.Network(network.routersByStatus(), network.openSessions(), usage.inputOctets(),
                        usage.outputOctets()));
    }
}
