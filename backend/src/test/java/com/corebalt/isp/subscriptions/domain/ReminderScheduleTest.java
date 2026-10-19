package com.corebalt.isp.subscriptions.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReminderScheduleTest {

    static final ZoneId NAIROBI = ZoneId.of("Africa/Nairobi");
    /** 17 Nov 2026, 14:30 in Nairobi. */
    static final Instant EXPIRES = Instant.parse("2026-11-17T11:30:00Z");

    @Test
    void daysBeforeExpiryAreTenantLocalDates() {
        assertThat(due("2026-11-13T20:59:00Z")).isNull(); // 13 Nov 23:59 local: four days before
        assertThat(due("2026-11-13T21:00:00Z")).isEqualTo(ReminderSchedule.Event.EXPIRING_3D); // 14 Nov 00:00 local
        assertThat(due("2026-11-15T21:00:00Z")).isEqualTo(ReminderSchedule.Event.EXPIRING_1D);
        assertThat(due("2026-11-16T21:00:00Z")).isEqualTo(ReminderSchedule.Event.DUE_TODAY);
        assertThat(due("2026-11-15T09:00:00Z")).isNull(); // two days before: no reminder
    }

    @Test
    void graceAndRestrictionFollowTheInstant() {
        assertThat(ReminderSchedule.due(EXPIRES, 2, NAIROBI, EXPIRES)).contains(ReminderSchedule.Event.GRACE_STARTED);
        assertThat(ReminderSchedule.due(EXPIRES, 0, NAIROBI, EXPIRES)).contains(ReminderSchedule.Event.RESTRICTED);
        assertThat(ReminderSchedule.due(EXPIRES, 2, NAIROBI, EXPIRES.plus(Duration.ofDays(2))))
                .contains(ReminderSchedule.Event.RESTRICTED);
        assertThat(ReminderSchedule.due(EXPIRES, 2, NAIROBI, EXPIRES.plus(Duration.ofDays(3)))).isEmpty();
    }

    @Test
    void M1_dedupeKeyIsEventSubscriptionAndExpiry() {
        final UUID id = UUID.fromString("01900000-0000-7000-8000-0000000b0001");
        assertThat(ReminderSchedule.dedupeKey(ReminderSchedule.Event.EXPIRING_3D, id, EXPIRES))
                .isEqualTo("EXPIRING_3D:01900000-0000-7000-8000-0000000b0001:2026-11-17T11:30:00Z");
    }

    private static ReminderSchedule.Event due(final String now) {
        return ReminderSchedule.due(EXPIRES, 2, NAIROBI, Instant.parse(now)).orElse(null);
    }
}
