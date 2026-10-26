package com.corebalt.isp.reporting.application;

import com.corebalt.isp.reporting.api.Gate1Dto;
import com.corebalt.isp.shared.tenant.TenantIterator;
import com.corebalt.isp.tenancy.api.TenantProfile;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Gate 1 in the background (ADR 0018): completes open restores and sets per-tenant gauges every minute,
 * so a missed restore pages within a minute or two of its 10 minutes running out. Gauges per tenant and
 * window (the last hour, the last 30 days), restores also per kind (CUT_OFF, GRACE_THROTTLE, FAIR_USE):
 * {@code isp.gate1.restores}, {@code isp.gate1.restores.slow} (slower than 60 seconds),
 * {@code isp.gate1.restores.missed}, {@code isp.gate1.restore.p95.seconds}; {@code isp.gate1.manual.reconnects}
 * per kind (STAFF_DISCONNECT, PAID_OFFLINE); {@code isp.gate1.matching.automatic.ratio}. A gauge is absent
 * while there is nothing to measure (p95, matching). Disabled in tests, which call {@link Gate1Service}.
 */
@Component
@ConditionalOnProperty(name = "isp.reporting.jobs.enabled", havingValue = "true", matchIfMissing = true)
class Gate1Jobs {

    private record Window(String name, Duration length) {}

    private static final List<Window> WINDOWS = List.of(new Window("1h", Duration.ofHours(1)), new Window("30d", Duration.ofDays(30)));

    private final TenantIterator tenants;
    private final Gate1Service gate1;
    private final TenantProfile tenantProfile;
    private final Clock clock;
    private final MultiGauge restores;
    private final MultiGauge slow;
    private final MultiGauge missed;
    private final MultiGauge p95;
    private final MultiGauge manual;
    private final MultiGauge matching;

    Gate1Jobs(final TenantIterator tenants, final Gate1Service gate1, final TenantProfile tenantProfile, final Clock clock,
              final MeterRegistry meters) {
        this.tenants = tenants;
        this.gate1 = gate1;
        this.tenantProfile = tenantProfile;
        this.clock = clock;
        this.restores = MultiGauge.builder("isp.gate1.restores")
                .description("Lines lifted to full speed by callback payments in the window, by kind (Gate 1)").register(meters);
        this.slow = MultiGauge.builder("isp.gate1.restores.slow")
                .description("Restores completed more than 60 seconds after the callback (Gate 1)").register(meters);
        this.missed = MultiGauge.builder("isp.gate1.restores.missed")
                .description("Restores with no completion within 10 minutes of the renewal: a paid customer still limited (Gate 1)")
                .register(meters);
        this.p95 = MultiGauge.builder("isp.gate1.restore.p95.seconds")
                .description("95th percentile of callback-to-restore seconds (Gate 1)").register(meters);
        this.manual = MultiGauge.builder("isp.gate1.manual.reconnects")
                .description("Staff disconnects after a payment and paid-but-offline reports (Gate 1)").register(meters);
        this.matching = MultiGauge.builder("isp.gate1.matching.automatic.ratio")
                .description("Share of callback payments matched without staff (Gate 1: above 0.98)").register(meters);
    }

    @Scheduled(fixedDelayString = "${isp.reporting.gate1.interval:1m}", initialDelayString = "${isp.reporting.jobs.initial-delay:1m}")
    void measure() {
        final List<MultiGauge.Row<?>> restoreRows = new ArrayList<>();
        final List<MultiGauge.Row<?>> slowRows = new ArrayList<>();
        final List<MultiGauge.Row<?>> missedRows = new ArrayList<>();
        final List<MultiGauge.Row<?>> p95Rows = new ArrayList<>();
        final List<MultiGauge.Row<?>> manualRows = new ArrayList<>();
        final List<MultiGauge.Row<?>> matchingRows = new ArrayList<>();
        tenants.forEachTenant("gate1", tenantId -> {
            final String code = tenantProfile.currentTenantCode();
            final Instant now = clock.instant();
            // gate1() completes the open restores first.
            for (final Window window : WINDOWS) {
                final Gate1Dto g = gate1.gate1(now.minus(window.length()), now);
                final Tags tags = Tags.of("tenant", code, "window", window.name());
                for (final Gate1Dto.KindRestores k : g.restoresByKind()) {
                    final Tags kind = tags.and("kind", k.kind());
                    restoreRows.add(MultiGauge.Row.of(kind, k.total()));
                    slowRows.add(MultiGauge.Row.of(kind, k.slowerThan60s()));
                    missedRows.add(MultiGauge.Row.of(kind, k.missed()));
                    if (k.p95Seconds() != null) {
                        p95Rows.add(MultiGauge.Row.of(kind, k.p95Seconds()));
                    }
                }
                manualRows.add(MultiGauge.Row.of(tags.and("kind", "STAFF_DISCONNECT"), g.reconnects().staffDisconnects()));
                manualRows.add(MultiGauge.Row.of(tags.and("kind", "PAID_OFFLINE"), g.reconnects().paidOfflineReports()));
                if (g.matching().automaticPercent() != null) {
                    matchingRows.add(MultiGauge.Row.of(tags, g.matching().automaticPercent() / 100.0));
                }
            }
        });
        restores.register(restoreRows, true);
        slow.register(slowRows, true);
        missed.register(missedRows, true);
        p95.register(p95Rows, true);
        manual.register(manualRows, true);
        matching.register(matchingRows, true);
    }
}
