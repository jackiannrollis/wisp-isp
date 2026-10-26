package com.corebalt.isp.subscriptions.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The outcome of a CSV import: every row was imported, in file order, as batch {@code batchId}.
 * Generated PPPoE passwords appear only in this response (ADR 0009); a row whose credentials were
 * kept from the old system ({@code credentialKept}) carries none, since the router already has
 * them. {@code activatedUntil} is set on a line the import activated (S11). Rows without a package
 * have no line.
 */
public record CustomerImportResultDto(UUID batchId, int imported, int activated, List<Row> rows) {

    public record Row(
            int row,
            UUID customerId,
            String number,
            String name,
            UUID subscriptionId,
            String pppoeUsername,
            String pppoePassword,
            boolean credentialKept,
            Instant activatedUntil) {}
}
