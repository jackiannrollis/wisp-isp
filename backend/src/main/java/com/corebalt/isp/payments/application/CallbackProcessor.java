package com.corebalt.isp.payments.application;

import com.corebalt.isp.payments.domain.MpesaCallbacks;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Processes one stored callback (P2), at most once: the row is locked and skipped once processed,
 * and a C2B or STK payment is deduplicated again by P1. A body that cannot be read is marked
 * processed with {@code last_error} so it surfaces for investigation instead of being retried.
 */
@Service
public class CallbackProcessor {

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final MpesaIngestion ingestion;
    private final StkService stk;
    private final Clock clock;

    CallbackProcessor(
            final JdbcClient jdbc,
            final JsonMapper json,
            final MpesaIngestion ingestion,
            final StkService stk,
            final Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.ingestion = ingestion;
        this.stk = stk;
        this.clock = clock;
    }

    @Transactional
    public void process(final UUID callbackId) {
        final Optional<Row> row = jdbc.sql("""
                        select kind, raw_body from payments_mpesa_callback
                        where id = :id and processed_at is null
                        for update skip locked
                        """)
                .param("id", callbackId)
                .query((rs, n) -> new Row(CallbackInbox.Kind.valueOf(rs.getString(1)), rs.getString(2)))
                .optional();
        if (row.isEmpty()) {
            return;
        }
        final JsonNode body;
        try {
            body = json.readTree(row.get().rawBody());
        } catch (RuntimeException e) {
            done(callbackId, null, "malformed body: not JSON");
            return;
        }
        try {
            final UUID paymentId = switch (row.get().kind()) {
                case C2B_VALIDATION, TXN_STATUS_TIMEOUT -> null;
                case C2B_CONFIRMATION -> ingestion.ingest(MpesaCallbacks.c2bConfirmation(body, row.get().rawBody())).paymentId();
                case STK_RESULT -> stk.onCallback(MpesaCallbacks.stkResult(body), row.get().rawBody());
                case TXN_STATUS_RESULT -> MpesaCallbacks.transactionStatus(body, row.get().rawBody())
                        .map(incoming -> ingestion.ingest(incoming).paymentId())
                        .orElse(null);
            };
            done(callbackId, paymentId, null);
        } catch (MpesaCallbacks.UnreadableCallback e) {
            done(callbackId, null, "malformed body: " + e.getMessage());
        }
    }

    private void done(final UUID callbackId, final UUID paymentId, final String error) {
        jdbc.sql("""
                        update payments_mpesa_callback
                        set processed_at = :now, attempts = attempts + 1, payment_id = :paymentId, last_error = :error
                        where id = :id
                        """)
                .param("now", Timestamp.from(clock.instant()))
                .param("paymentId", paymentId)
                .param("error", error)
                .param("id", callbackId)
                .update();
    }

    private record Row(CallbackInbox.Kind kind, String rawBody) {}
}
