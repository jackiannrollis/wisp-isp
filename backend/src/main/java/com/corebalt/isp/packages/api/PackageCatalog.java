package com.corebalt.isp.packages.api;

import com.corebalt.isp.shared.money.Money;
import java.util.Optional;
import java.util.UUID;

/** The packages module's interface for other modules; always scoped to the current tenant (T1). */
public interface PackageCatalog {

    Optional<PackageSummary> find(UUID packageId);

    /** Case-insensitive; names are unique per tenant. */
    Optional<PackageSummary> findByName(String name);

    /** The terms a renewal is charged and timed by. */
    Optional<PackageTerms> terms(UUID packageId);

    /** The speeds a CoA sets (N4, N5). */
    Optional<PackageSpeeds> speeds(UUID packageId);

    /** {@code graceDays} null means "use the tenant default" (docs/04 Grace policy). */
    record PackageTerms(
            UUID id, String name, Money price, int durationMinutes, Integer graceDays, boolean prepaid, boolean sellableAsPppoe) {}

    /** kbit/s; throttle speeds are null when the package has none (it stays at full speed when THROTTLED). */
    record PackageSpeeds(int uploadKbps, int downloadKbps, Integer throttleUploadKbps, Integer throttleDownloadKbps) {}

    /** What other modules may know about a package. */
    record PackageSummary(UUID id, String name, String type, boolean active) {

        public boolean sellableAsPppoe() {
            return active && "PPPOE".equals(type);
        }
    }
}
