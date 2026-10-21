package com.corebalt.isp.subscriptions.domain;

import java.time.Duration;
import java.time.Instant;

/**
 * The access rules of docs/04, in Java. The SQL function {@code subscription_access_v2()} implements
 * the same rules for FreeRADIUS ({@code v_radius_auth_v6}) and the staff screens;
 * SubscriptionAccessAgreementIT proves they agree on profile, next change and Session-Timeout for the
 * same inputs and the same instant (S3). Change both or neither.
 *
 * <p>Order (S2, S7): PENDING, SUSPENDED, TERMINATED give REJECT; PAUSED gives RESTRICTED until
 * {@code pause_until}, after which the line counts as ACTIVE with the paused time added to its
 * expiry, whether or not a job has recorded the resume yet (E4). While the dates still give access
 * (before the end of grace), a reached data cap gives RESTRICTED and an exceeded fair-use allowance
 * THROTTLED, until the next renewal starts a new cycle (docs/09 step 8b, Q13, Q14). Otherwise: before
 * expiry FULL; inside grace FULL or THROTTLED by the tenant's grace mode; after grace RESTRICTED.
 */
public final class AccessPolicy {

    /** S4: Session-Timeout never exceeds one day. */
    public static final long MAX_SESSION_SECONDS = 86_400;

    private AccessPolicy() {}

    public static Decision evaluate(final Facts line, final GraceMode graceMode, final Instant now) {
        final boolean pauseOver = line.state() == SubscriptionState.PAUSED && !now.isBefore(line.pauseUntil());
        final SubscriptionState state = pauseOver ? SubscriptionState.ACTIVE : line.state();
        final Instant expiresAt = pauseOver ? resumedExpiry(line) : line.expiresAt();

        switch (state) {
            case PENDING, SUSPENDED, TERMINATED -> {
                return new Decision(AccessProfile.REJECT, state, expiresAt, null);
            }
            case PAUSED -> {
                return new Decision(AccessProfile.RESTRICTED, state, expiresAt, line.pauseUntil());
            }
            case ACTIVE -> {
                final Instant graceEndsAt = expiresAt.plus(Duration.ofDays(line.graceDays()));
                // The cycle's usage limits hold until the renewal that starts a new cycle, so the next
                // change by the clock is still the end of grace (or none, once restricted).
                if (now.isBefore(graceEndsAt) && line.dataCapReached()) {
                    return new Decision(AccessProfile.RESTRICTED, state, expiresAt, null);
                }
                if (now.isBefore(graceEndsAt) && line.fairUseExceeded()) {
                    return new Decision(AccessProfile.THROTTLED, state, expiresAt, graceEndsAt);
                }
                if (now.isBefore(expiresAt)) {
                    final boolean changesAtExpiry = graceMode == GraceMode.THROTTLED || line.graceDays() == 0;
                    return new Decision(AccessProfile.FULL, state, expiresAt, changesAtExpiry ? expiresAt : graceEndsAt);
                }
                if (now.isBefore(graceEndsAt)) {
                    final AccessProfile profile =
                            graceMode == GraceMode.THROTTLED ? AccessProfile.THROTTLED : AccessProfile.FULL;
                    return new Decision(profile, state, expiresAt, graceEndsAt);
                }
                return new Decision(AccessProfile.RESTRICTED, state, expiresAt, null);
            }
            default -> throw new IllegalStateException("unknown state " + state);
        }
    }

    /** S7: a pause that ran to its end gives back exactly the paused time. */
    public static Instant resumedExpiry(final Facts line) {
        return line.expiresAt().plus(Duration.between(line.pausedAt(), line.pauseUntil()));
    }

    /**
     * What the policy needs to know about a line. {@code fairUseExceeded} and {@code dataCapReached} say
     * whether the usage of the line's current cycle has reached its package's fair-use allowance or
     * data cap ({@link FairUse}); a line without limits, or whose cycle start is unknown, has neither.
     */
    public record Facts(
            SubscriptionState state, Instant expiresAt, int graceDays, Instant pausedAt, Instant pauseUntil,
            boolean fairUseExceeded, boolean dataCapReached) {

        /** A line with no usage limit reached. */
        public Facts(final SubscriptionState state, final Instant expiresAt, final int graceDays, final Instant pausedAt,
                     final Instant pauseUntil) {
            this(state, expiresAt, graceDays, pausedAt, pauseUntil, false, false);
        }

        public Facts withUsage(final FairUse.Limits limits) {
            return new Facts(state, expiresAt, graceDays, pausedAt, pauseUntil, limits.fairUseExceeded(),
                    limits.dataCapReached());
        }

        public Facts withExpiresAt(final Instant newExpiresAt) {
            return new Facts(state, newExpiresAt, graceDays, pausedAt, pauseUntil, fairUseExceeded, dataCapReached);
        }
    }

    /**
     * The profile now, the state and expiry it was judged on (they differ from the stored ones only
     * for a pause that is over but not yet recorded), and when the profile next changes (null: not
     * by the clock).
     */
    public record Decision(
            AccessProfile profile,
            SubscriptionState effectiveState,
            Instant effectiveExpiresAt,
            Instant nextChangeAt) {

        /** S4: seconds to the next change, at least 1, capped at one day; null when rejected. */
        public Long sessionTimeoutSeconds(final Instant now) {
            if (profile == AccessProfile.REJECT) {
                return null;
            }
            if (nextChangeAt == null) {
                return MAX_SESSION_SECONDS;
            }
            final Duration left = Duration.between(now, nextChangeAt);
            final long seconds = left.getSeconds() + (left.getNano() > 0 ? 1 : 0);
            return Math.min(MAX_SESSION_SECONDS, Math.max(1, seconds));
        }
    }
}
