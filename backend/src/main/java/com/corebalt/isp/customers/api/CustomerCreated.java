package com.corebalt.isp.customers.api;

import com.corebalt.isp.shared.outbox.DomainEvent;
import java.time.Instant;
import java.util.UUID;

/** A customer was registered (docs/02). Carries IDs only (E3). */
public record CustomerCreated(UUID eventId, UUID tenantId, Instant occurredAt, UUID customerId, String number)
        implements DomainEvent {}
