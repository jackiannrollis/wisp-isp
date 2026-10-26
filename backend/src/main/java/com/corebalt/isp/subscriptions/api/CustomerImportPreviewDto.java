package com.corebalt.isp.subscriptions.api;

import com.corebalt.isp.shared.money.MoneyDto;
import java.time.Instant;
import java.util.List;

/**
 * The dry run of a CSV import (ADR 0014): what the file would do, validated like the import itself,
 * with nothing written. {@code confirmation} identifies this file and these options; an import
 * that activates lines (S11) must send it back, so the summary was seen for the very file that is
 * committed. Never carries a password.
 */
public record CustomerImportPreviewDto(
        int rows,
        int lines,
        int credentialsKept,
        int legacyRefs,
        Activation activation,
        List<Warning> warnings,
        String confirmation) {

    /**
     * Lines the file activates with a {@code paid_until}: already {@code expired} ones land on the pay
     * page at once, {@code future} ones are served until their date. {@code valueGranted} is the
     * package price pro rata of the time still ahead of each future date (B6 rounding), the service
     * given without a payment on the platform; null when nothing activates.
     */
    public record Activation(
            int total, int expired, int future, Instant earliestPaidUntil, Instant latestPaidUntil, MoneyDto valueGranted) {}

    /** Something to look at before committing; {@code rows} is how many rows it concerns. */
    public record Warning(String code, String message, int rows) {}
}
