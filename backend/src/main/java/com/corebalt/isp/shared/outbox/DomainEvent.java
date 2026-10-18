package com.corebalt.isp.shared.outbox;

import java.time.Instant;
import java.util.UUID;

/**
 * A published business event (E3): a Java record in the owning module's {@code api} package, named
 * {@code <Aggregate><Verb>} in the past tense, carrying IDs rather than entities.
 */
public interface DomainEvent {

    UUID eventId();

    UUID tenantId();

    Instant occurredAt();

    /** Schema version; part of the routing key ({@code <module>.<Event>.v<version>}). */
    default int version() {
        return 1;
    }
}
