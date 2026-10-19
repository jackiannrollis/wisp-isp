package com.corebalt.isp.subscriptions.api;

import java.time.Instant;
import java.util.UUID;

/**
 * A line as staff see it. {@code accessProfile} and {@code displayStatus} are derived at read time
 * (S1) and shown as returned; the frontend never computes them. Never carries the PPPoE password
 * (T4, ADR 0009).
 */
public record SubscriptionDto(
        UUID id,
        UUID customerId,
        UUID packageId,
        UUID routerId,
        String pppoeUsername,
        String state,
        String accessProfile,
        String displayStatus,
        Instant expiresAt,
        int graceDays,
        Instant pausedAt,
        Instant pauseUntil,
        Instant activatedAt,
        Instant terminatedAt,
        Instant createdAt,
        Instant updatedAt) {}
