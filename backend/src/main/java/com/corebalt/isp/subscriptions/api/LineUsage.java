package com.corebalt.isp.subscriptions.api;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Port for a line's usage in its current cycle (docs/09 step 8b), implemented by the network module,
 * which owns the usage roll-up (N7). It lives here because network already depends on this module's
 * events, so subscriptions may not depend on network (conventions.md, Backend). Current tenant only.
 */
public interface LineUsage {

    /**
     * Download plus upload counted since {@code cycleStartedAt}; zero when the counter holds another
     * cycle or {@code cycleStartedAt} is null (the cycle start is unknown, so the line is not metered).
     */
    long cycleOctets(UUID subscriptionId, Instant cycleStartedAt);

    /** The same for many lines at once (a page of the staff list); lines without usage are absent. */
    Map<UUID, Long> cycleOctets(Map<UUID, Instant> cycleStarts);
}
