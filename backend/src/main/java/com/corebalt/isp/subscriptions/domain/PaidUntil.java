package com.corebalt.isp.subscriptions.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Optional;

/**
 * The {@code paid_until} column of the cutover import (ADR 0014). Old systems export dates, so a
 * date alone ({@code 2026-11-15}) is accepted and means {@code timeOfDay} in the tenant's zone: an
 * expiry in daytime, when customers can pay and staff can help. A date-time without a zone is read
 * in the tenant's zone too; one with an offset or {@code Z} as given. Other shapes, {@code DD/MM/YYYY}
 * above all, are refused rather than guessed.
 */
public final class PaidUntil {

    public static final String RULE = "YYYY-MM-DD (a date: expiry at the chosen time of day, tenant time) or an ISO 8601 date-time";

    private PaidUntil() {}

    public static Optional<Instant> parse(final String raw, final ZoneId zone, final LocalTime timeOfDay) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        final String value = raw.strip();
        try {
            return Optional.of(LocalDate.parse(value).atTime(timeOfDay).atZone(zone).toInstant());
        } catch (DateTimeParseException notADate) {
            // fall through
        }
        try {
            return Optional.of(OffsetDateTime.parse(value).toInstant());
        } catch (DateTimeParseException notOffset) {
            // fall through
        }
        try {
            return Optional.of(LocalDateTime.parse(value).atZone(zone).toInstant());
        } catch (DateTimeParseException notLocal) {
            return Optional.empty();
        }
    }
}
