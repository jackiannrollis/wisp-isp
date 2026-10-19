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
     * renewal sells (the pending package when one is scheduled, S10). A line that renews moves its expiry
     * past the period, so every line counted here is still unrenewed; {@code outstanding} is the sum of
     * what each one's wallet still lacks for its renewal (B5), the portal's "pay to renew". Each line is
     * set against its customer's whole balance, so two due lines of one customer can both count it.
     */
    RenewalsDue renewalsDue(Instant from, Instant to);

    /**
     * Lines stored ACTIVE whose expiry fell before {@code before} and that have not renewed since: the
     * churn list. {@code amount} is the price their next renewal sells.
     */
    Lapsed lapsed(Instant before);

    record LineCounts(long total, Map<String, Long> byState, Map<String, Long> byDisplayStatus) {}

    record RenewalsDue(long lines, Money amount, Money outstanding) {}

    record Lapsed(long lines, Money amount) {}
}
