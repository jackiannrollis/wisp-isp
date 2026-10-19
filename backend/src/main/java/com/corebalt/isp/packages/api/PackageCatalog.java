package com.corebalt.isp.packages.api;

import java.util.Optional;
import java.util.UUID;

/** The packages module's interface for other modules; always scoped to the current tenant (T1). */
public interface PackageCatalog {

    Optional<PackageSummary> find(UUID packageId);

    /** Case-insensitive; names are unique per tenant. */
    Optional<PackageSummary> findByName(String name);

    /** What other modules may know about a package. */
    record PackageSummary(UUID id, String name, String type, boolean active) {

        public boolean sellableAsPppoe() {
            return active && "PPPOE".equals(type);
        }
    }
}
