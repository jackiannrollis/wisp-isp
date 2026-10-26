package com.corebalt.isp.subscriptions.api;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * What a CSV import did (ADR 0014): its batch, with the totals its preview showed, and the customers
 * it created, so the "we've moved" SMS reaches exactly them. Scoped to the current tenant (T1).
 */
public interface CustomerImports {

    Optional<ImportBatch> batch(UUID batchId);

    /** The customers the batch created, in file order. */
    List<UUID> customersOf(UUID batchId);

    /** One import: the preview's totals as they were when it was committed. */
    record ImportBatch(
            UUID id,
            Instant createdAt,
            UUID routerId,
            int rows,
            int lines,
            int credentialsKept,
            int legacyRefs,
            CustomerImportPreviewDto.Activation activation,
            List<CustomerImportPreviewDto.Warning> warnings) {}
}
