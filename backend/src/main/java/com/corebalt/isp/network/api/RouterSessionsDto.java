package com.corebalt.isp.network.api;

import java.time.Instant;
import java.util.List;

/**
 * A router's sessions two ways (docs/06 Accounting): radacct's open sessions for it, and the router's own
 * /ppp/active list as the tunnel worker last saw it. {@code router} is null until a poll has read it; its
 * {@code ageSeconds} says how old it is. A refresh is {@code POST /api/v1/routers/{id}/poll}, then a
 * re-read a few seconds later.
 */
public record RouterSessionsDto(
        List<OpenSession> radius,
        Snapshot router,
        List<String> onlyInRadius,
        List<String> onlyOnRouter) {

    public record OpenSession(String username, String acctSessionId, String framedIp, Instant startedAt, Instant lastHeardAt) {}

    public record Snapshot(int count, List<String> usernames, Instant fetchedAt, long ageSeconds) {}
}
