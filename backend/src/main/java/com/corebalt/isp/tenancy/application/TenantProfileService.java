package com.corebalt.isp.tenancy.application;

import com.corebalt.isp.shared.tenant.TenantContext;
import com.corebalt.isp.tenancy.api.TenantProfile;
import com.corebalt.isp.tenancy.domain.Tenant;
import com.corebalt.isp.tenancy.infrastructure.TenantRepository;
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
        final var tenantId = TenantContext.requireTenantId();
        return tenants.findById(tenantId)
                .map(Tenant::getCode)
                .orElseThrow(() -> new IllegalStateException("T1: no tenant row for " + tenantId));
    }
}
