package com.corebalt.isp.network.api;

import com.corebalt.isp.shared.outbox.DomainEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * A router left the platform for good (N10, ADR 0015): its secret is revoked, its sessions closed and its
 * lines moved to {@code replacementRouterId} (null when it had none). Notifications clears its open
 * offline alert. Carries IDs only (E3).
 */
public record RouterRetired(
        UUID eventId, UUID tenantId, Instant occurredAt, UUID routerId, UUID replacementRouterId, int linesMoved)
        implements DomainEvent {}
