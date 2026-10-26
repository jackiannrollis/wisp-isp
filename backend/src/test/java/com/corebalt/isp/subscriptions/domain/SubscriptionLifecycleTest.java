package com.corebalt.isp.subscriptions.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SubscriptionLifecycleTest {

    static final Instant T0 = Instant.parse("2026-11-01T14:30:00Z");
    static final Duration MONTH = Duration.ofDays(30);
    static final UUID PKG = UUID.fromString("01900000-0000-7000-8000-0000000000e1");

    @Test
    void S5_firstRenewalActivatesFromPaymentTime() {
        final Subscription line = pending();
        final Subscription.Renewal renewal = line.renew(MONTH, 2, GraceMode.FULL_SPEED, PKG, T0);
        assertThat(renewal.from()).isEqualTo(SubscriptionState.PENDING);
        assertThat(line.getState()).isEqualTo(SubscriptionState.ACTIVE);
        assertThat(line.getExpiresAt()).isEqualTo(T0.plus(MONTH));
        assertThat(line.getActivatedAt()).isEqualTo(T0);
    }

    @Test
    void S5_earlyRenewalExtendsFromCurrentExpiryKeepingTheClockTime() {
        final Subscription line = active(T0);
        line.renew(MONTH, 0, GraceMode.FULL_SPEED, PKG, T0.plus(Duration.ofDays(25)).plus(Duration.ofHours(3)));
        assertThat(line.getExpiresAt()).isEqualTo(T0.plus(MONTH).plus(MONTH));
    }

    @Test
    void S5_renewalInsideGraceStillExtendsFromExpiry() {
        final Subscription line = active(T0);
        line.renew(MONTH, 2, GraceMode.FULL_SPEED, PKG, T0.plus(MONTH)); // grace days copied for the next cycle
        line.renew(MONTH, 2, GraceMode.FULL_SPEED, PKG, T0.plus(MONTH).plus(MONTH).plus(Duration.ofDays(1)));
        assertThat(line.getExpiresAt()).isEqualTo(T0.plus(MONTH.multipliedBy(3)));
    }

    @Test
    void S5_renewalPastGraceStartsFromPayment() {
        final Subscription line = active(T0);
        final Instant paidAt = T0.plus(MONTH).plus(Duration.ofDays(5)).plus(Duration.ofMinutes(7));
        line.renew(MONTH, 2, GraceMode.FULL_SPEED, PKG, paidAt);
        assertThat(line.getExpiresAt()).isEqualTo(paidAt.plus(MONTH));
    }

    @Test
    void S6_graceDaysAreCopiedAtEachRenewal() {
        final Subscription line = active(T0);
        line.renew(MONTH, 3, GraceMode.FULL_SPEED, PKG, T0.plus(Duration.ofDays(29)));
        assertThat(line.getGraceDays()).isEqualTo(3);
    }

    @Test
    void S7_manualResumeGivesBackTheTimeActuallyPaused() {
        final Subscription line = active(T0);
        final Instant pausedAt = T0.plus(Duration.ofDays(10));
        line.pause(pausedAt.plus(Duration.ofDays(20)), pausedAt, 30);
        final Subscription.Transition resumed = line.resume(pausedAt.plus(Duration.ofDays(4)));
        assertThat(resumed.recorded()).isFalse();
        assertThat(line.getState()).isEqualTo(SubscriptionState.ACTIVE);
        assertThat(line.getExpiresAt()).isEqualTo(T0.plus(MONTH).plus(Duration.ofDays(4)));
        assertThat(line.getPausedAt()).isNull();
    }

    @Test
    void S7_resumeAfterPauseUntilRecordsWhatTheViewAlreadyApplied() {
        final Subscription line = active(T0);
        final Instant pausedAt = T0.plus(Duration.ofDays(10));
        final Instant until = pausedAt.plus(Duration.ofDays(5));
        line.pause(until, pausedAt, 30);
        final AccessPolicy.Decision beforeRecording =
                AccessPolicy.evaluate(line.facts(), GraceMode.FULL_SPEED, until.plus(Duration.ofDays(2)));

        final Subscription.Transition resumed = line.resume(until.plus(Duration.ofDays(2)));
        assertThat(resumed.recorded()).isTrue();
        assertThat(line.getExpiresAt()).isEqualTo(T0.plus(MONTH).plus(Duration.ofDays(5)));
        assertThat(line.getExpiresAt()).isEqualTo(beforeRecording.effectiveExpiresAt());
    }

    @Test
    void S7_onlyAnActiveLineWithTimeLeftAndWithinTheLimitCanBePaused() {
        assertThatThrownBy(() -> pending().pause(T0.plus(Duration.ofDays(1)), T0, 30))
                .isInstanceOf(LifecycleViolation.class).hasMessageStartingWith("S7");
        final Subscription expired = active(T0.minus(Duration.ofDays(40)));
        assertThatThrownBy(() -> expired.pause(T0.plus(Duration.ofDays(1)), T0, 30))
                .hasMessageContaining("time remaining");
        assertThatThrownBy(() -> active(T0).pause(T0.plus(Duration.ofDays(31)), T0, 30))
                .hasMessageContaining("at most 30 days");
    }

    @Test
    void suspendingAPausedLineGivesBackThePausedTimeFirst() {
        final Subscription line = active(T0);
        line.pause(T0.plus(Duration.ofDays(20)), T0.plus(Duration.ofDays(1)), 30);
        final Subscription.Transition suspended = line.suspend(T0.plus(Duration.ofDays(3)));
        assertThat(suspended.from()).isEqualTo(SubscriptionState.PAUSED);
        assertThat(line.getState()).isEqualTo(SubscriptionState.SUSPENDED);
        assertThat(line.getExpiresAt()).isEqualTo(T0.plus(MONTH).plus(Duration.ofDays(2)));
        assertThat(line.getPauseUntil()).isNull();
    }

    @Test
    void S8_liftingAHoldReEvaluatesDates() {
        final Subscription line = active(T0);
        line.suspend(T0.plus(Duration.ofDays(1)));
        final Instant lifted = T0.plus(MONTH).plus(Duration.ofDays(10));
        line.liftSuspension(lifted);
        assertThat(AccessPolicy.evaluate(line.facts(), GraceMode.FULL_SPEED, lifted).profile())
                .isEqualTo(AccessProfile.RESTRICTED);
    }

    @Test
    void terminationIsFinalAndFollowsTheDiagram() {
        final Subscription line = active(T0);
        line.terminate(T0.plus(Duration.ofDays(1)));
        assertThat(line.getTerminatedAt()).isEqualTo(T0.plus(Duration.ofDays(1)));
        assertThatThrownBy(() -> line.liftSuspension(T0)).isInstanceOf(LifecycleViolation.class);
        assertThatThrownBy(() -> line.renew(MONTH, 0, GraceMode.FULL_SPEED, PKG, T0)).hasMessageStartingWith("S5");
        assertThatThrownBy(() -> pending().terminate(T0)).isInstanceOf(LifecycleViolation.class);
    }

    private static Subscription pending() {
        return Subscription.open(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "CB000001", false, T0.minus(Duration.ofDays(1)));
    }

    /** ACTIVE, activated at {@code activatedAt}, expiring a month later, no grace. */
    private static Subscription active(final Instant activatedAt) {
        final Subscription line = pending();
        line.renew(MONTH, 0, GraceMode.FULL_SPEED, PKG, activatedAt);
        return line;
    }
}
