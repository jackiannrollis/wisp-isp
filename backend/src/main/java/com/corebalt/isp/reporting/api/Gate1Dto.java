package com.corebalt.isp.reporting.api;

import java.time.Instant;

/**
 * Gate 1 for one tenant over [from, to) (docs/09, ADR 0018): the pilot passes with payment matching above
 * 98% automatic, no restore slower than 60 seconds after the callback and no reconnects needing hands.
 *
 * <ul>
 *   <li>{@code matching}: payments that arrived by M-Pesa callback, by how they were matched;
 *       {@code automaticPercent} is null when there were none.
 *   <li>{@code restores}: a callback payment that renewed a RESTRICTED line, measured from the platform
 *       recording the callback to its part done: the renewal when the line had no live session (its
 *       next login is FULL), otherwise the router answering the Disconnect or CoA, or a new session,
 *       within 10 minutes of the renewal; {@code missed} had none in time, {@code pending} is still inside
 *       its 10 minutes. Seconds are null when no restore completed.
 *   <li>{@code reconnects}: alerts that stand for hands needed (a proxy), plus the missed restores.
 * </ul>
 */
public record Gate1Dto(Instant from, Instant to, Matching matching, Restores restores, Reconnects reconnects,
                       Targets targets) {

    public record Matching(long automatic, long byStaff, long unmatched, Double automaticPercent) {}

    public record Restores(long total, long completed, long pending, long missed, long slowerThan60s,
                           Long p95Seconds, Long maxSeconds) {}

    public record Reconnects(long coaFailedAlerts, long routerOfflineAlerts, long missedRestores) {}

    /** Each Gate 1 criterion over the period; null when there is nothing to judge yet. */
    public record Targets(Boolean matchingAbove98, Boolean noRestoreSlowerThan60s, Boolean noReconnectsNeedingHands) {}
}
