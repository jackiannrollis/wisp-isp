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
 * Gate 1 in the background (ADR 0018): completes open restores every minute, and every 5 minutes sets
 * per-tenant gauges over the last hour and the last 30 days for the Platform dashboard and
 * {@code CorebaltGate1RestoreLate}: {@code isp.gate1.restores}, {@code isp.gate1.restores.late} (slower
 * than 60 seconds or missed), {@code isp.gate1.restore.p95.seconds} and
 * {@code isp.gate1.matching.automatic.ratio} (absent while there is nothing to measure). Disabled in
 * tests, which call {@link Gate1Service} directly.
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
    private final MultiGauge late;
    private final MultiGauge p95;
    private final MultiGauge matching;

    Gate1Jobs(final TenantIterator tenants, final Gate1Service gate1, final TenantProfile tenantProfile, final Clock clock,
              final MeterRegistry meters) {
        this.tenants = tenants;
        this.gate1 = gate1;
        this.tenantProfile = tenantProfile;
        this.clock = clock;
        this.restores = MultiGauge.builder("isp.gate1.restores")
                .description("Restores of RESTRICTED lines by callback payments in the window (Gate 1)").register(meters);
        this.late = MultiGauge.builder("isp.gate1.restores.late")
                .description("Restores slower than 60 seconds after the callback, or missed (Gate 1)").register(meters);
        this.p95 = MultiGauge.builder("isp.gate1.restore.p95.seconds")
                .description("95th percentile of callback-to-restore seconds (Gate 1)").register(meters);
        this.matching = MultiGauge.builder("isp.gate1.matching.automatic.ratio")
                .description("Share of callback payments matched without staff (Gate 1: above 0.98)").register(meters);
    }

    @Scheduled(fixedDelayString = "${isp.reporting.gate1.complete-interval:1m}", initialDelayString = "${isp.reporting.jobs.initial-delay:1m}")
    void completeRestores() {
        tenants.forEachTenant("gate1-restores", tenantId -> gate1.completeOpenRestores());
    }

    @Scheduled(fixedDelayString = "${isp.reporting.gate1.gauges-interval:5m}", initialDelayString = "${isp.reporting.jobs.initial-delay:1m}")
    void gauges() {
        final List<MultiGauge.Row<?>> restoreRows = new ArrayList<>();
        final List<MultiGauge.Row<?>> lateRows = new ArrayList<>();
        final List<MultiGauge.Row<?>> p95Rows = new ArrayList<>();
        final List<MultiGauge.Row<?>> matchingRows = new ArrayList<>();
        tenants.forEachTenant("gate1-gauges", tenantId -> {
            final String code = tenantProfile.currentTenantCode();
            final Instant now = clock.instant();
            for (final Window window : WINDOWS) {
                final Gate1Dto g = gate1.gate1(now.minus(window.length()), now);
                final Tags tags = Tags.of("tenant", code, "window", window.name());
                restoreRows.add(MultiGauge.Row.of(tags, g.restores().total()));
                lateRows.add(MultiGauge.Row.of(tags, g.restores().slowerThan60s() + g.restores().missed()));
                if (g.restores().p95Seconds() != null) {
                    p95Rows.add(MultiGauge.Row.of(tags, g.restores().p95Seconds()));
                }
                if (g.matching().automaticPercent() != null) {
                    matchingRows.add(MultiGauge.Row.of(tags, g.matching().automaticPercent() / 100.0));
                }
            }
        });
        restores.register(restoreRows, true);
        late.register(lateRows, true);
        p95.register(p95Rows, true);
        matching.register(matchingRows, true);
    }
}
