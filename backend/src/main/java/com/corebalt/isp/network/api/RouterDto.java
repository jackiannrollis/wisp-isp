package com.corebalt.isp.network.api;

import java.time.Instant;
import java.util.UUID;

/**
 * A router as staff see it: never its RADIUS secret or REST password. {@code status} is
 * NOT_MONITORED (no REST credential), UNKNOWN (not polled yet), ONLINE or OFFLINE (3 missed polls);
 * {@code lastAccountingAt} is the secondary "last heard" from RADIUS accounting.
 */
public record RouterDto(
        UUID id,
        String name,
        String tunnelIp,
        String status,
        String restUsername,
        Instant lastSeenAt,
        Instant offlineSince,
        Instant lastPolledAt,
        String lastPollError,
        Instant lastAccountingAt,
        String routerosVersion,
        String boardName,
        Long uptimeSeconds,
        Integer cpuLoad,
        Instant createdAt,
        Instant updatedAt) {}
