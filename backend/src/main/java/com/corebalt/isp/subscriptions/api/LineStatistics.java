package com.corebalt.isp.subscriptions.api;

import com.corebalt.isp.shared.money.Money;
import java.time.Instant;
import java.util.Map;

/** Line counts and renewals due for the dashboard (docs/09 step 8); current tenant only. */
public interface LineStatistics {

    /**
     * Lines by stored state and by display status, the latter derived at {@code now} by
     * {@code AccessPolicy} (S1), never stored. Every name appears, with zero when no line has it.
     */
    LineCounts counts(Instant now);

    /**
     * ACTIVE lines whose {@code expires_at} falls in [from, to), and the sum of the price their next
     * renewal sells (the pending package when one is scheduled, S10).
     */
    RenewalsDue renewalsDue(Instant from, Instant to);

    record LineCounts(long total, Map<String, Long> byState, Map<String, Long> byDisplayStatus) {}

    record RenewalsDue(long lines, Money amount) {}
}
