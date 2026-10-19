package com.corebalt.isp.tenancy.application;

import com.corebalt.isp.shared.tenant.TenantContext;
import com.corebalt.isp.tenancy.api.TenantProfile;
import com.corebalt.isp.tenancy.domain.Tenant;
import com.corebalt.isp.tenancy.infrastructure.TenantRepository;
import java.time.ZoneId;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class TenantProfileService implements TenantProfile {

    private final TenantRepository tenants;

    TenantProfileService(final TenantRepository tenants) {
        this.tenants = tenants;
    }

    @Override
    @Transactional(readOnly = true)
    public String currentTenantCode() {
        return current().getCode();
    }

    @Override
    @Transactional(readOnly = true)
    public TenantPolicy currentPolicy() {
        final Tenant tenant = current();
        return new TenantPolicy(
                tenant.getCode(),
                ZoneId.of(tenant.getTimeZone()),
                tenant.getDefaultGraceDays(),
                tenant.getGraceMode(),
                tenant.getMaxPauseDays());
    }

    private Tenant current() {
        final var tenantId = TenantContext.requireTenantId();
        return tenants.findById(tenantId)
                .orElseThrow(() -> new IllegalStateException("T1: no tenant row for " + tenantId));
    }
}
