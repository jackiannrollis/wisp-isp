package com.corebalt.isp.shared.tenant;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The only sanctioned way to touch every tenant's data (conventions.md, T1): visits each active
 * tenant under its own tenant context, logged. A failure for one tenant does not stop the others.
 */
@Component
public class TenantIterator {

    private static final Logger log = LoggerFactory.getLogger(TenantIterator.class);

    private final TenantDirectory tenantDirectory;

    public TenantIterator(final TenantDirectory tenantDirectory) {
        this.tenantDirectory = tenantDirectory;
    }

    public void forEachTenant(final String reason, final Consumer<UUID> work) {
        final List<UUID> tenantIds =
                TenantContext.callAsPlatform(reason, tenantDirectory::activeTenantIds);
        log.debug("Iterating {} tenants for {}", tenantIds.size(), reason);
        for (final UUID tenantId : tenantIds) {
            try {
                TenantContext.runAsTenant(tenantId, () -> work.accept(tenantId));
            } catch (RuntimeException e) {
                log.error("{} failed for tenant {}", reason, tenantId, e);
            }
        }
    }
}
