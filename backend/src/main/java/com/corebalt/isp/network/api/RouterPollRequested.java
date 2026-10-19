package com.corebalt.isp.network.api;

import com.corebalt.isp.shared.outbox.DomainEvent;
import java.time.Instant;
import java.util.UUID;

/** Staff asked for a poll now; the tunnel worker consumes it (ADR 0011). */
public record RouterPollRequested(UUID eventId, UUID tenantId, Instant occurredAt, UUID routerId) implements DomainEvent {}
