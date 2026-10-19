package com.corebalt.isp.subscriptions.api;

import java.util.List;
import java.util.UUID;

/**
 * The outcome of a CSV import: every row was imported, in file order. PPPoE passwords of new lines
 * appear only in this response (ADR 0009); rows without a package have no line.
 */
public record CustomerImportResultDto(int imported, List<Row> rows) {

    public record Row(
            int row,
            UUID customerId,
            String number,
            String name,
            UUID subscriptionId,
            String pppoeUsername,
            String pppoePassword) {}
}
