package com.corebalt.isp.network.api;

import com.corebalt.isp.shared.outbox.DomainEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * A live session must change because its line's access changed (N5, docs/06): one event per open
 * session, published by the API, sent only by the tunnel worker (ADR 0011). {@code action} COA sets
 * {@code rateLimit} (N4) in place; DISCONNECT drops the session so the next login gets the new pool
 * and address list. The session is named by its router, username and Acct-Session-Id; the router's
 * address and secret are read by the worker when it sends, never carried here (T4).
 */
public record NetworkAccessChanged(
        UUID eventId,
        UUID tenantId,
        Instant occurredAt,
        UUID subscriptionId,
        UUID routerId,
        String username,
        String acctSessionId,
        Action action,
        String rateLimit,
        String reason)
        implements DomainEvent {

    public enum Action {
        COA,
        DISCONNECT
    }
}
