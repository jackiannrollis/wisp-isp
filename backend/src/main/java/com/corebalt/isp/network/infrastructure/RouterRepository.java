package com.corebalt.isp.network.infrastructure;

import com.corebalt.isp.network.domain.Router;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** Row-level security limits every query to the current tenant (T1). */
public interface RouterRepository extends JpaRepository<Router, UUID> {

    /** An active router with this name: a retired one's name may be reused by its replacement (N10). */
    @Query("select r from Router r where lower(r.name) = lower(:name) and r.retiredAt is null")
    Optional<Router> findByNameIgnoreCase(String name);

    boolean existsByIdAndRetiredAtIsNull(UUID id);

    Page<Router> findByRetiredAtIsNull(Pageable page);
}
