package com.corebalt.isp.tenancy.application;

import com.corebalt.isp.shared.tenant.TenantContext;
import com.corebalt.isp.tenancy.api.BrandingDto;
import com.corebalt.isp.tenancy.infrastructure.TenantRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Branding of the current tenant; defaults when the tenant row is not there yet. */
@Service
public class BrandingQuery {

    static final BrandingDto DEFAULT = new BrandingDto("Corebalt", null, "#0f766e");

    private final TenantRepository tenants;
    private final BrandingMapper mapper;

    BrandingQuery(final TenantRepository tenants, final BrandingMapper mapper) {
        this.tenants = tenants;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public BrandingDto currentTenantBranding() {
        return tenants.findById(TenantContext.requireTenantId()).map(mapper::toDto).orElse(DEFAULT);
    }
}
