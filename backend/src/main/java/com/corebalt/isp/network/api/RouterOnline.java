package com.corebalt.isp.network.api;

import com.corebalt.isp.shared.outbox.DomainEvent;
import java.time.Instant;
import java.util.UUID;

/** An OFFLINE router answered again: its alert is cleared. */
public record RouterOnline(UUID eventId, UUID tenantId, Instant occurredAt, UUID routerId, Instant offlineSince) implements DomainEvent {}
