package com.corebalt.isp.subscriptions.domain;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

/**
 * Which reminder is due for an ACTIVE line now (docs/04 Reminders), evaluated hourly. Days before
 * expiry are counted in the tenant's local dates; grace and restriction by the instant. A window
 * missed (the job was down) is skipped rather than sent late, and RESTRICTED is raised only during
 * the first day after grace, so long-lapsed lines are not messaged again.
 */
public final class ReminderSchedule {

    public static final Duration RESTRICTED_LOOKBACK = Duration.ofDays(1);

    private ReminderSchedule() {}

    public enum Event {
        EXPIRING_3D,
        EXPIRING_1D,
        DUE_TODAY,
        GRACE_STARTED,
        RESTRICTED
    }

    public static Optional<Event> due(
            final Instant expiresAt, final int graceDays, final ZoneId zone, final Instant now) {
        if (now.isBefore(expiresAt)) {
            final long daysLeft =
                    ChronoUnit.DAYS.between(LocalDate.ofInstant(now, zone), LocalDate.ofInstant(expiresAt, zone));
            if (daysLeft == 3) {
                return Optional.of(Event.EXPIRING_3D);
            }
            if (daysLeft == 1) {
                return Optional.of(Event.EXPIRING_1D);
            }
            if (daysLeft == 0) {
                return Optional.of(Event.DUE_TODAY);
            }
            return Optional.empty();
        }
        final Instant graceEndsAt = expiresAt.plus(Duration.ofDays(graceDays));
        if (now.isBefore(graceEndsAt)) {
            return Optional.of(Event.GRACE_STARTED);
        }
        if (now.isBefore(graceEndsAt.plus(RESTRICTED_LOOKBACK))) {
            return Optional.of(Event.RESTRICTED);
        }
        return Optional.empty();
    }

    /** M1: event, subscription and {@code expires_at}; a renewal moves expires_at and so re-arms. */
    public static String dedupeKey(final Event event, final UUID subscriptionId, final Instant expiresAt) {
        return event.name() + ":" + subscriptionId + ":" + expiresAt;
    }
}
