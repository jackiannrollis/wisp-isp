package com.corebalt.isp.tenancy.api;

import com.corebalt.isp.shared.outbox.DomainEvent;
import java.time.Instant;
import java.util.UUID;

/** Test-only event; lives in a module {@code api} package like every real event (E3). */
public record TestThingHappened(UUID eventId, UUID tenantId, Instant occurredAt, String note)
        implements DomainEvent {}
