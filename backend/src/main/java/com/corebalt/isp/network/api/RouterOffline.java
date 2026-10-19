package com.corebalt.isp.network.api;

import com.corebalt.isp.shared.outbox.DomainEvent;
import java.time.Instant;
import java.util.UUID;

/** Three polls in a row got no answer (docs/06 Monitoring): staff are alerted. */
public record RouterOffline(UUID eventId, UUID tenantId, Instant occurredAt, UUID routerId, Instant offlineSince) implements DomainEvent {}
