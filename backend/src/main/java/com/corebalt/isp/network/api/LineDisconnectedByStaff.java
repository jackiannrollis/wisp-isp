package com.corebalt.isp.network.api;

import com.corebalt.isp.shared.outbox.DomainEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * A staff member disconnected a line's live sessions by hand, with a reason (the admin app's "Disconnect
 * sessions"); the router is asked through NetworkAccessChanged as for any Disconnect (N5). Reporting
 * counts it as a manual reconnect when it follows a payment within 2 hours (Gate 1, ADR 0018).
 * {@code actingPlatformAdminId} is set when Corebalt support did it while impersonating. Carries IDs (E3).
 */
public record LineDisconnectedByStaff(
        UUID eventId,
        UUID tenantId,
        Instant occurredAt,
        UUID subscriptionId,
        UUID customerId,
        UUID staffId,
        UUID actingPlatformAdminId,
        String reason,
        int sessions)
        implements DomainEvent {}
