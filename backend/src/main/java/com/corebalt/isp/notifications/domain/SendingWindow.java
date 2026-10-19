package com.corebalt.isp.notifications.domain;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * M2: reminders go out only between {@code start} and {@code end} in the tenant's time zone
 * (default 07:00 to 21:00); outside it they wait for the next start. Payment and restore messages
 * are immediate.
 */
public record SendingWindow(LocalTime start, LocalTime end, ZoneId zone) {

    public Instant scheduleFor(final Instant now, final boolean immediate) {
        if (immediate) {
            return now;
        }
        final ZonedDateTime local = now.atZone(zone);
        final LocalTime time = local.toLocalTime();
        if (!time.isBefore(start) && time.isBefore(end)) {
            return now;
        }
        final ZonedDateTime next = time.isBefore(start)
                ? local.with(start)
                : local.plusDays(1).with(start);
        return next.toInstant();
    }
}
