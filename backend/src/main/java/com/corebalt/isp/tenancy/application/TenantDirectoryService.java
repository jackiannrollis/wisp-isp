package com.corebalt.isp.tenancy.application;

import com.corebalt.isp.shared.tenant.TenantDirectory;
import com.corebalt.isp.tenancy.domain.TenantStatus;
import com.corebalt.isp.tenancy.infrastructure.TenantRepository;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Tenants that platform-scoped work visits: trial and active ones, not suspended ones. */
@Service
class TenantDirectoryService implements TenantDirectory {

    private final TenantRepository tenants;

    TenantDirectoryService(final TenantRepository tenants) {
        this.tenants = tenants;
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> activeTenantIds() {
        return tenants.findIdsByStatusIn(EnumSet.of(TenantStatus.TRIAL, TenantStatus.ACTIVE));
    }
}
