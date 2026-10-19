package com.corebalt.isp.network.infrastructure;

import com.corebalt.isp.network.domain.Router;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** Row-level security limits every query to the current tenant (T1). */
public interface RouterRepository extends JpaRepository<Router, UUID> {

    @Query("select r from Router r where lower(r.name) = lower(:name)")
    Optional<Router> findByNameIgnoreCase(String name);
}
