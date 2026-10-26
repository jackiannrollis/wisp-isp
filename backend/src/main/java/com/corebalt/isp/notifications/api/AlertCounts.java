package com.corebalt.isp.notifications.api;

import java.time.Instant;

/**
 * Staff alerts that stand for a reconnect needing hands, for Gate 1 (docs/09, ADR 0018); current tenant
 * only. A proxy: an alert raised is a problem someone had to look at, not proof that they did.
 */
public interface AlertCounts {

    /** CoA or Disconnect failures (N5) and routers gone offline, raised in [from, to). */
    Raised raised(Instant from, Instant to);

    record Raised(long coaFailed, long routerOffline) {}
}
