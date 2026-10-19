package com.corebalt.isp.packages.infrastructure;

import com.corebalt.isp.packages.domain.ServicePackage;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** Row-level security limits every query to the current tenant (T1). */
public interface PackageRepository extends JpaRepository<ServicePackage, UUID> {

    @Query("select p from ServicePackage p where lower(p.name) = lower(:name)")
    Optional<ServicePackage> findByNameIgnoreCase(String name);

    Page<ServicePackage> findByActive(boolean active, Pageable page);
}
