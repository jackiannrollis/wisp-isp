package com.corebalt.isp.packages.api;

import com.corebalt.isp.shared.outbox.DomainEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * A package was created, revised, deactivated or reactivated (docs/02). Carries IDs only (E3);
 * consumers read the current terms through {@link PackageCatalog}.
 */
public record PackageChanged(UUID eventId, UUID tenantId, Instant occurredAt, UUID packageId, Change change)
        implements DomainEvent {

    public enum Change {
        CREATED,
        REVISED,
        DEACTIVATED,
        REACTIVATED
    }
}
