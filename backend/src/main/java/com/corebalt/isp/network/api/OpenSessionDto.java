package com.corebalt.isp.network.api;

import java.time.Instant;
import java.util.UUID;

/**
 * A session open in radacct, across the tenant's routers (docs/06 Accounting). {@code onRouter} compares it
 * with the router's own /ppp/active list from the tunnel worker's last poll (ADR 0011): true or false,
 * or null when that router has not been polled. {@code customerId} is null when the username matches no
 * line. Usage is not here: screens read it only from network_usage_daily (N7).
 */
public record OpenSessionDto(
        String username,
        String acctSessionId,
        String framedIp,
        String callingStationId,
        Instant startedAt,
        Instant lastHeardAt,
        UUID routerId,
        String routerName,
        UUID subscriptionId,
        UUID customerId,
        Boolean onRouter) {}
