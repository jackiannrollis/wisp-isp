package com.corebalt.isp.shared.tenant;

import java.util.List;
import java.util.UUID;

/** Lists the tenants that platform-scoped work must visit. Implemented by the tenancy module. */
public interface TenantDirectory {

    List<UUID> activeTenantIds();
}
