package com.corebalt.isp.subscriptions.application;

import com.corebalt.isp.shared.tenant.TenantContext;
import com.corebalt.isp.subscriptions.api.CreateLineRequest;
import com.corebalt.isp.subscriptions.api.ProbeLines;
import com.corebalt.isp.subscriptions.domain.LifecycleViolation;
import com.corebalt.isp.subscriptions.infrastructure.SubscriptionRepository;
import com.corebalt.isp.tenancy.api.ProbeTenant;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The probe tenant's lines (ADR 0019): opened like any line (a generated username, a PPPoE password issued
 * into network_radius_credential and never returned, ADR 0009), then activated with cause PROBE (S12). The
 * only caller of {@link LifecycleService#activateForProbe}.
 */
@Service
class ProbeLineService implements ProbeLines {

    private final LineService lineService;
    private final LifecycleService lifecycle;
    private final SubscriptionRepository lines;

    ProbeLineService(final LineService lineService, final LifecycleService lifecycle, final SubscriptionRepository lines) {
        this.lineService = lineService;
        this.lifecycle = lifecycle;
        this.lines = lines;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProbeLine> lines() {
        requireProbeTenant();
        return lines.findAll(Sort.by("createdAt")).stream()
                .map(l -> new ProbeLine(l.getId(), l.getPppoeUsername(), l.getState().name(), l.getExpiresAt()))
                .toList();
    }

    @Override
    @Transactional
    public ProbeLine open(final UUID customerId, final UUID packageId, final UUID routerId, final Instant expiresAt) {
        requireProbeTenant();
        final UUID id = lineService.open(new CreateLineRequest(customerId, packageId, routerId)).subscription().id();
        final var line = lifecycle.activateForProbe(id, expiresAt);
        return new ProbeLine(line.id(), line.pppoeUsername(), line.state(), line.expiresAt());
    }

    private static void requireProbeTenant() {
        if (!ProbeTenant.is(TenantContext.requireTenantId())) {
            throw new LifecycleViolation("S12: probe lines belong to the probe tenant only.");
        }
    }
}
