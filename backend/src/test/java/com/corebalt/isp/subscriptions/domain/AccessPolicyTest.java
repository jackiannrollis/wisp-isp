package com.corebalt.isp.subscriptions.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class AccessPolicyTest {

    static final Instant NOW = Instant.parse("2026-11-17T11:30:00Z");

    @Test
    void S2_storedHoldStatesAreRejectedWithoutTimeout() {
        for (final SubscriptionState state :
                new SubscriptionState[] {SubscriptionState.PENDING, SubscriptionState.SUSPENDED, SubscriptionState.TERMINATED}) {
            final AccessPolicy.Decision decision = evaluate(state, NOW.plus(days(10)), 2, GraceMode.FULL_SPEED);
            assertThat(decision.profile()).isEqualTo(AccessProfile.REJECT);
            assertThat(decision.sessionTimeoutSeconds(NOW)).isNull();
        }
    }

    @Test
    void S2_beforeExpiryIsFull() {
        assertThat(evaluate(SubscriptionState.ACTIVE, NOW.plusSeconds(1), 0, GraceMode.FULL_SPEED).profile())
                .isEqualTo(AccessProfile.FULL);
    }

    @Test
    void S2_insideGraceFollowsTheTenantsGraceMode() {
        final Instant expired = NOW.minus(Duration.ofHours(1));
        assertThat(evaluate(SubscriptionState.ACTIVE, expired, 2, GraceMode.FULL_SPEED).profile()).isEqualTo(AccessProfile.FULL);
        assertThat(evaluate(SubscriptionState.ACTIVE, expired, 2, GraceMode.THROTTLED).profile())
                .isEqualTo(AccessProfile.THROTTLED);
    }

    @Test
    void N3_pastGraceIsRestrictedWithADayTimeout() {
        final AccessPolicy.Decision decision =
                evaluate(SubscriptionState.ACTIVE, NOW.minus(days(2)), 2, GraceMode.FULL_SPEED);
        assertThat(decision.profile()).isEqualTo(AccessProfile.RESTRICTED);
        assertThat(decision.sessionTimeoutSeconds(NOW)).isEqualTo(86_400);
    }

    @Test
    void S2_graceEndsExactlyAtExpiryPlusGraceDays() {
        final Instant expiresAt = NOW.minus(days(2)).plusMillis(1);
        assertThat(evaluate(SubscriptionState.ACTIVE, expiresAt, 2, GraceMode.FULL_SPEED).profile()).isEqualTo(AccessProfile.FULL);
        assertThat(evaluate(SubscriptionState.ACTIVE, NOW.minus(days(2)), 2, GraceMode.FULL_SPEED).profile())
                .isEqualTo(AccessProfile.RESTRICTED);
    }

    @Test
    void S4_timeoutRunsToExpiryWhenGraceIsThrottledOrZero() {
        final Instant expiresAt = NOW.plus(Duration.ofMinutes(90)).plusMillis(400);
        assertThat(evaluate(SubscriptionState.ACTIVE, expiresAt, 0, GraceMode.FULL_SPEED).sessionTimeoutSeconds(NOW))
                .isEqualTo(5_401);
        assertThat(evaluate(SubscriptionState.ACTIVE, expiresAt, 2, GraceMode.THROTTLED).sessionTimeoutSeconds(NOW))
                .isEqualTo(5_401);
    }

    @Test
    void S4_timeoutRunsToGraceEndWhenGraceIsFullSpeedAndIsCapped() {
        final Instant expiresAt = NOW.plus(Duration.ofHours(1));
        final AccessPolicy.Decision decision = evaluate(SubscriptionState.ACTIVE, expiresAt, 2, GraceMode.FULL_SPEED);
        assertThat(decision.nextChangeAt()).isEqualTo(expiresAt.plus(days(2)));
        assertThat(decision.sessionTimeoutSeconds(NOW)).isEqualTo(86_400);
    }

    @Test
    void S7_pausedIsRestrictedUntilPauseUntil() {
        final AccessPolicy.Facts paused = new AccessPolicy.Facts(
                SubscriptionState.PAUSED, NOW.plus(days(10)), 0, NOW.minus(days(1)), NOW.plus(Duration.ofHours(2)));
        final AccessPolicy.Decision decision = AccessPolicy.evaluate(paused, GraceMode.FULL_SPEED, NOW);
        assertThat(decision.profile()).isEqualTo(AccessProfile.RESTRICTED);
        assertThat(decision.sessionTimeoutSeconds(NOW)).isEqualTo(7_200);
    }

    @Test
    void S7_E4_pauseOverResumesWithThePausedTimeAddedWithoutAnyJob() {
        // Expired a day ago, but paused for 3 days ending an hour ago: the expiry moves 3 days on.
        final AccessPolicy.Facts paused = new AccessPolicy.Facts(
                SubscriptionState.PAUSED, NOW.minus(days(1)), 0, NOW.minus(days(3)).minus(Duration.ofHours(1)),
                NOW.minus(Duration.ofHours(1)));
        final AccessPolicy.Decision decision = AccessPolicy.evaluate(paused, GraceMode.FULL_SPEED, NOW);
        assertThat(decision.profile()).isEqualTo(AccessProfile.FULL);
        assertThat(decision.effectiveState()).isEqualTo(SubscriptionState.ACTIVE);
        assertThat(decision.effectiveExpiresAt()).isEqualTo(NOW.plus(days(2)));

        // Recording the resume changes nothing about access.
        final AccessPolicy.Decision recorded = AccessPolicy.evaluate(
                new AccessPolicy.Facts(SubscriptionState.ACTIVE, NOW.plus(days(2)), 0, null, null), GraceMode.FULL_SPEED, NOW);
        assertThat(recorded).isEqualTo(decision);
    }

    @Test
    void displayStatusDerivesExpiringGraceAndRestricted() {
        assertThat(display(NOW.plus(days(4)), 2)).isEqualTo(DisplayStatus.ACTIVE);
        assertThat(display(NOW.plus(days(3)).minusSeconds(1), 2)).isEqualTo(DisplayStatus.EXPIRING);
        assertThat(display(NOW.minus(days(1)), 2)).isEqualTo(DisplayStatus.GRACE);
        assertThat(display(NOW.minus(days(3)), 2)).isEqualTo(DisplayStatus.RESTRICTED);
    }

    @Test
    void S2_pastTheFairUseAllowanceTheLineIsThrottledUntilGraceEnds() {
        final AccessPolicy.Decision decision = usage(NOW.plus(days(5)), 2, GraceMode.FULL_SPEED, FairUse.limits(1_000L, null, 1_000));
        assertThat(decision.profile()).isEqualTo(AccessProfile.THROTTLED);
        // The throttle lasts until a renewal; by the clock the line next changes when grace ends.
        assertThat(decision.nextChangeAt()).isEqualTo(NOW.plus(days(7)));
        assertThat(usage(NOW.plus(days(5)), 2, GraceMode.FULL_SPEED, FairUse.limits(1_000L, null, 999)).profile())
                .as("one byte under the allowance").isEqualTo(AccessProfile.FULL);
    }

    @Test
    void Q14_atTheDataCapTheLineIsRestrictedUntilItRenews() {
        final AccessPolicy.Decision decision =
                usage(NOW.plus(days(5)), 2, GraceMode.THROTTLED, FairUse.limits(1_000L, 5_000L, 5_000));
        assertThat(decision.profile()).as("the cap wins over fair use").isEqualTo(AccessProfile.RESTRICTED);
        assertThat(decision.nextChangeAt()).isNull();
        assertThat(decision.sessionTimeoutSeconds(NOW)).isEqualTo(86_400);
    }

    @Test
    void S2_usageNeverLiftsAHoldOrAPause_norOutlivesGrace() {
        final FairUse.Limits capped = FairUse.limits(1_000L, 5_000L, 9_000);
        assertThat(AccessPolicy.evaluate(new AccessPolicy.Facts(SubscriptionState.SUSPENDED, NOW.plus(days(5)), 2, null, null)
                .withUsage(capped), GraceMode.FULL_SPEED, NOW).profile()).isEqualTo(AccessProfile.REJECT);
        assertThat(AccessPolicy.evaluate(new AccessPolicy.Facts(SubscriptionState.PAUSED, NOW.plus(days(5)), 2,
                NOW.minus(days(1)), NOW.plus(days(1))).withUsage(FairUse.limits(1_000L, null, 1_000)),
                GraceMode.FULL_SPEED, NOW).profile()).isEqualTo(AccessProfile.RESTRICTED);
        assertThat(usage(NOW.minus(days(3)), 2, GraceMode.FULL_SPEED, FairUse.limits(1_000L, null, 1_000)).profile())
                .as("past grace the dates restrict anyway").isEqualTo(AccessProfile.RESTRICTED);
    }

    @Test
    void unlimitedPackagesAreNeverThrottledByUsage() {
        assertThat(FairUse.limits(null, null, Long.MAX_VALUE)).isEqualTo(FairUse.Limits.NONE);
    }

    private static AccessPolicy.Decision usage(final Instant expiresAt, final int graceDays, final GraceMode mode,
                                               final FairUse.Limits limits) {
        return AccessPolicy.evaluate(
                new AccessPolicy.Facts(SubscriptionState.ACTIVE, expiresAt, graceDays, null, null).withUsage(limits), mode, NOW);
    }

    private static DisplayStatus display(final Instant expiresAt, final int graceDays) {
        final AccessPolicy.Facts facts = new AccessPolicy.Facts(SubscriptionState.ACTIVE, expiresAt, graceDays, null, null);
        return DisplayStatus.of(facts, AccessPolicy.evaluate(facts, GraceMode.THROTTLED, NOW), NOW);
    }

    private static AccessPolicy.Decision evaluate(
            final SubscriptionState state, final Instant expiresAt, final int graceDays, final GraceMode mode) {
        return AccessPolicy.evaluate(new AccessPolicy.Facts(state, expiresAt, graceDays, null, null), mode, NOW);
    }

    private static Duration days(final long days) {
        return Duration.ofDays(days);
    }
}
