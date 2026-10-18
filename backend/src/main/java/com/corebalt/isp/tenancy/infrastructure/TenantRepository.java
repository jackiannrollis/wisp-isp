package com.corebalt.isp.tenancy.infrastructure;

import com.corebalt.isp.tenancy.domain.Tenant;
import com.corebalt.isp.tenancy.domain.TenantStatus;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface TenantRepository extends JpaRepository<Tenant, UUID> {

    @Query("select t.id from Tenant t where t.status in :statuses order by t.id")
    List<UUID> findIdsByStatusIn(Collection<TenantStatus> statuses);
}
