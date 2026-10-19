package com.corebalt.isp.network.api;

import com.corebalt.isp.shared.outbox.DomainEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * The router took a CoA or Disconnect (N5), or answered that the session had already ended, in which
 * case the next login gets the new profile anyway. Clears the line's COA_FAILED alert.
 */
public record SessionReauthorized(
        UUID eventId,
        UUID tenantId,
        Instant occurredAt,
        UUID subscriptionId,
        UUID routerId,
        String acctSessionId,
        NetworkAccessChanged.Action action,
        boolean sessionAlreadyGone)
        implements DomainEvent {}
