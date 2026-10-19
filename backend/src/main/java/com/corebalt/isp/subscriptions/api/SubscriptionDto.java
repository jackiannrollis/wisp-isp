package com.corebalt.isp.subscriptions.api;

import java.time.Instant;
import java.util.UUID;

/** A line as staff see it. Never carries the PPPoE password (T4, ADR 0009). */
public record SubscriptionDto(
        UUID id,
        UUID customerId,
        UUID packageId,
        UUID routerId,
        String pppoeUsername,
        String state,
        Instant expiresAt,
        int graceDays,
        Instant createdAt,
        Instant updatedAt) {}
