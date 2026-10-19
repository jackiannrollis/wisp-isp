package com.corebalt.isp.network.api;

import com.corebalt.isp.shared.outbox.DomainEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * A CoA or Disconnect got no acknowledgement after its retries, or the router refused it (N5): staff
 * are alerted. The session keeps its old profile until its Session-Timeout (S4, at most a day).
 */
public record SessionReauthorizationFailed(
        UUID eventId,
        UUID tenantId,
        Instant occurredAt,
        UUID subscriptionId,
        UUID routerId,
        String acctSessionId,
        NetworkAccessChanged.Action action,
        int attempts,
        String reason)
        implements DomainEvent {}
