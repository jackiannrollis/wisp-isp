package com.corebalt.isp.subscriptions.infrastructure;

import com.corebalt.isp.shared.money.Money;
import com.corebalt.isp.shared.money.MoneyDto;
import com.corebalt.isp.shared.tenant.TenantContext;
import com.corebalt.isp.subscriptions.api.CustomerImportPreviewDto;
import com.corebalt.isp.subscriptions.api.CustomerImportPreviewDto.Activation;
import com.corebalt.isp.subscriptions.api.CustomerImportPreviewDto.Warning;
import com.corebalt.isp.subscriptions.api.CustomerImports;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * The import batches of the current tenant (ADR 0014): one row per CSV import with the totals of
 * its preview, one row per customer it created. Append-only; row-level security scopes both (T1).
 */
@Component
public class ImportBatches implements CustomerImports {

    private final JdbcClient jdbc;
    private final JsonMapper json;

    ImportBatches(final JdbcClient jdbc, final JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void create(final UUID id, final UUID routerId, final UUID importedBy, final CustomerImportPreviewDto summary,
                       final Instant now) {
        final Activation activation = summary.activation();
        final Money value = activation.valueGranted() == null ? null : activation.valueGranted().toMoney();
        jdbc.sql("""
                        insert into subscriptions_import_batch
                            (id, tenant_id, router_id, imported_by, rows_total, lines_total, credentials_kept, legacy_refs,
                             activated, activated_expired, activated_future, earliest_paid_until, latest_paid_until,
                             value_granted_minor, currency, warnings, created_at)
                        values (:id, :tenantId, :routerId, :importedBy, :rows, :lines, :kept, :legacy,
                                :activated, :expired, :future, :earliest, :latest,
                                :value, :currency, cast(:warnings as jsonb), :now)
                        """)
                .param("id", id)
                .param("tenantId", TenantContext.requireTenantId())
                .param("routerId", routerId)
                .param("importedBy", importedBy)
                .param("rows", summary.rows())
                .param("lines", summary.lines())
                .param("kept", summary.credentialsKept())
                .param("legacy", summary.legacyRefs())
                .param("activated", activation.total())
                .param("expired", activation.expired())
                .param("future", activation.future())
                .param("earliest", timestamp(activation.earliestPaidUntil()))
                .param("latest", timestamp(activation.latestPaidUntil()))
                .param("value", value == null ? 0L : value.minorUnits())
                .param("currency", value == null ? "KES" : value.currency().getCurrencyCode())
                .param("warnings", json.writeValueAsString(summary.warnings()))
                .param("now", Timestamp.from(now))
                .update();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void addRow(final UUID batchId, final int row, final UUID customerId, final UUID subscriptionId) {
        jdbc.sql("""
                        insert into subscriptions_import_batch_row (batch_id, tenant_id, row_number, customer_id, subscription_id)
                        values (:batchId, :tenantId, :row, :customerId, :subscriptionId)
                        """)
                .param("batchId", batchId)
                .param("tenantId", TenantContext.requireTenantId())
                .param("row", row)
                .param("customerId", customerId)
                .param("subscriptionId", subscriptionId)
                .update();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ImportBatch> batch(final UUID batchId) {
        return jdbc.sql("""
                        select id, created_at, router_id, rows_total, lines_total, credentials_kept, legacy_refs,
                               activated, activated_expired, activated_future, earliest_paid_until, latest_paid_until,
                               value_granted_minor, currency, warnings::text
                        from subscriptions_import_batch where id = :id
                        """)
                .param("id", batchId)
                .query(this::row)
                .optional();
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> customersOf(final UUID batchId) {
        return jdbc.sql("select customer_id from subscriptions_import_batch_row where batch_id = :id order by row_number")
                .param("id", batchId)
                .query(UUID.class)
                .list();
    }

    /** The batch's totals for its audit row: the same numbers the preview showed, no row contents (A3). */
    public static Map<String, Object> auditDetail(final CustomerImportPreviewDto summary) {
        final Activation activation = summary.activation();
        final Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("rows", summary.rows());
        detail.put("lines", summary.lines());
        detail.put("credentialsKept", summary.credentialsKept());
        detail.put("legacyRefs", summary.legacyRefs());
        detail.put("activated", activation.total());
        detail.put("activatedExpired", activation.expired());
        detail.put("activatedFuture", activation.future());
        detail.put("earliestPaidUntil", activation.earliestPaidUntil() == null ? null : activation.earliestPaidUntil().toString());
        detail.put("latestPaidUntil", activation.latestPaidUntil() == null ? null : activation.latestPaidUntil().toString());
        detail.put("valueGranted", activation.valueGranted() == null ? null
                : activation.valueGranted().amount() + " " + activation.valueGranted().currency());
        detail.put("warnings", summary.warnings().stream().map(Warning::code).toList());
        return detail;
    }

    private ImportBatch row(final ResultSet rs, final int n) throws SQLException {
        final long valueMinor = rs.getLong(13);
        final int activated = rs.getInt(8);
        final Activation activation = new Activation(activated, rs.getInt(9), rs.getInt(10),
                instant(rs.getTimestamp(11)), instant(rs.getTimestamp(12)),
                activated == 0 ? null : new MoneyDto(valueMinor, rs.getString(14)));
        final List<Warning> warnings = Arrays.asList(json.readValue(rs.getString(15), Warning[].class));
        return new ImportBatch(rs.getObject(1, UUID.class), rs.getTimestamp(2).toInstant(), rs.getObject(3, UUID.class),
                rs.getInt(4), rs.getInt(5), rs.getInt(6), rs.getInt(7), activation, warnings);
    }

    private static Timestamp timestamp(final Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Instant instant(final Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
