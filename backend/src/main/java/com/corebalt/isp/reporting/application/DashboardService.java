package com.corebalt.isp.reporting.application;

import com.corebalt.isp.network.api.NetworkStatistics;
import com.corebalt.isp.payments.api.PaymentTotals;
import com.corebalt.isp.reporting.api.DashboardDto;
import com.corebalt.isp.shared.money.Money;
import com.corebalt.isp.shared.money.MoneyDto;
import com.corebalt.isp.shared.security.CurrentActor;
import com.corebalt.isp.shared.security.Permissions;
import com.corebalt.isp.shared.web.InvalidRequestException;
import com.corebalt.isp.subscriptions.api.LineStatistics;
import com.corebalt.isp.tenancy.api.TenantProfile;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
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

    private DashboardDto.Revenue revenue(final Instant start, final Instant end, final LocalDate first,
                                         final LocalDate lastShown, final ZoneId zone) {
        final LineStatistics.RenewalsDue due = lines.renewalsDue(start, end);
        final LineStatistics.Lapsed lapsed = lines.lapsed(start);
        final PaymentTotals.Collected collected = payments.collected(start, end);
        return new DashboardDto.Revenue(due.lines(), MoneyDto.of(due.amount()), MoneyDto.of(due.outstanding()),
                lapsed.lines(), MoneyDto.of(lapsed.amount()), collected.payments(), MoneyDto.of(collected.amount()),
                collected.unmatchedPayments(), MoneyDto.of(collected.unmatchedAmount()),
                byDay(payments.collectedByDay(start, end, zone), first, lastShown));
    }

    /** One entry per day from {@code first} to {@code last}, a day without payments as zero. */
    static List<DashboardDto.DayCollected> byDay(final List<PaymentTotals.Day> days, final LocalDate first,
                                                 final LocalDate last) {
        final Map<LocalDate, PaymentTotals.Day> found = days.stream()
                .collect(Collectors.toMap(PaymentTotals.Day::day, Function.identity()));
        final List<DashboardDto.DayCollected> all = new ArrayList<>();
        for (LocalDate day = first; !day.isAfter(last); day = day.plusDays(1)) {
            final PaymentTotals.Day d = found.get(day);
            all.add(d == null ? new DashboardDto.DayCollected(day, 0, MoneyDto.of(Money.zero(Money.KES)))
                    : new DashboardDto.DayCollected(day, d.payments(), MoneyDto.of(d.amount())));
        }
        return all;
    }

    private DashboardDto.Suspense suspense() {
        final PaymentTotals.Suspense queue = payments.suspense();
        return new DashboardDto.Suspense(queue.payments(), MoneyDto.of(queue.amount()));
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
        final NetworkStatistics.UsageTotals usage = network.usage(first, last);
        return new DashboardDto(
                first,
                last,
                zone.getId(),
                now,
                new DashboardDto.Lines(counts.total(), counts.byState(), counts.byDisplayStatus(), counts.pendingStale()),
                CurrentActor.has(Permissions.FINANCE_READ)
                        ? revenue(start, end, first, last.isAfter(today) ? today : last, zone) : null,
                CurrentActor.has(Permissions.FINANCE_READ) || CurrentActor.has(Permissions.PAYMENTS_ASSIGN)
                        ? suspense() : null,
                new DashboardDto.Network(network.routersByStatus(), network.openSessions(), usage.inputOctets(),
                        usage.outputOctets()));
    }
}
