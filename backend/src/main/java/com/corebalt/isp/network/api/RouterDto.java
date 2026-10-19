package com.corebalt.isp.network.api;

import java.time.Instant;
import java.util.UUID;

/** A router as staff see it. Never carries the RADIUS secret (T4). */
public record RouterDto(UUID id, String name, String tunnelIp, Instant createdAt, Instant updatedAt) {}
