package com.corebalt.isp.payments;

import static org.assertj.core.api.Assertions.assertThat;

import com.corebalt.isp.support.Api;
import com.corebalt.isp.support.Catalog;
import com.corebalt.isp.support.IntegrationTest;
import com.corebalt.isp.support.OwnerSql;
import com.corebalt.isp.support.TestTenants;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/** Statement reconciliation, both directions plus uncredited payments (docs/05 Reconciliation, P4). */
class ReconciliationIT extends IntegrationTest {

    /** An organisation-portal style export: title lines, then the table. All values are fakes. */
    static final String STATEMENT = """
            Organisation Name:,Lab ISP
            Time Period:,17-11-2026 - 17-11-2026
            Receipt No.,Completion Time,Initiation Time,Details,Transaction Status,Paid In,Withdrawn,Balance
            QKR0000001,2026-11-17 14:30:12,2026-11-17 14:30:10,Pay Bill from 2547*****126 - JOHN Acc. X,Completed,"2,500.00",,10000.00
            QKR0000003,2026-11-17 15:00:00,2026-11-17 14:59:58,Pay Bill from 2547*****555 - MARY Acc. Y,Completed,1000.00,,11000.00
            QKR0000004,2026-11-17 14:40:00,2026-11-17 14:39:58,Pay Bill from 2547*****777 - PETER Acc. Z,Completed,500.00,,11500.00
            QKROUT0001,2026-11-17 16:00:00,2026-11-17 15:59:58,Withdrawal,Completed,,500.00,11000.00
            QKR0000005,2026-11-17 16:10:00,2026-11-17 16:09:58,Pay Bill from 2547*****888 - ANN Acc. W,Failed,300.00,,11000.00
            """;

    @Value("${local.server.port}")
    int port;

    @Autowired JdbcClient jdbc;
    @Autowired TransactionTemplate tx;

    TestTenants.Created tenant;
    Api api;
    String customer;

    @BeforeEach
    void tenant() {
        tenant = TestTenants.createWithCode(jdbc, tx);
        api = new Api(port, tenant.id());
        customer = Catalog.customer(api, "Wanjiku Kamau", "0712345678");
        api.put("/api/v1/settings/mpesa", """
                {"shortcode":"600984","shortcodeType":"PAYBILL","sandbox":true,"consumerKey":"fake-key",
                 "consumerSecret":"fake-secret","passkey":"fake-passkey","initiatorName":"fake-initiator",
                 "securityCredential":"fake-security-credential"}
                """);
    }

    @Test
    void theReportListsMissingExtraAndUncreditedPayments() {
        // On the statement and recorded: no line.
        manual("QKR0000001", 250_000, "2026-11-17T11:30:12Z");
        // Recorded but not on the statement: extra.
        manual("QKR0000002", 100_000, "2026-11-17T12:00:00Z");
        // On the statement, recorded and matched ten minutes ago, but never credited: uncredited.
        OwnerSql.client().sql("""
                insert into payments_payment (id, tenant_id, channel, provider_txn_id, amount_minor, received_at, status,
                                              customer_id, matched_at, raw_payload)
                values (:id, :t, 'MPESA_PAYBILL', 'QKR0000004', 50000, '2026-11-17T11:40:00Z', 'MATCHED',
                        cast(:c as uuid), now() - interval '10 minutes', '{}')
                """).param("id", UUID.randomUUID()).param("t", tenant.id()).param("c", customer).update();

        final Api.Response response = api.postCsv("/api/v1/payments/reconciliations", STATEMENT);
        assertThat(response.status()).as(response.body()).isEqualTo(201);
        final JsonNode run = response.json();
        assertThat(run.get("statementRows").asInt()).isEqualTo(3);
        assertThat(run.get("report").get("missing")).hasSize(1);
        assertThat(run.get("report").get("missing").get(0).get("receipt").asString()).isEqualTo("QKR0000003");
        assertThat(run.get("report").get("missing").get(0).get("paidInMinor").asLong()).isEqualTo(100_000);
        assertThat(run.get("report").get("extra")).hasSize(1);
        assertThat(run.get("report").get("extra").get(0).get("reference").asString()).isEqualTo("QKR0000002");
        assertThat(run.get("report").get("uncredited")).hasSize(1);
        assertThat(run.get("report").get("uncredited").get(0).get("reference").asString()).isEqualTo("QKR0000004");

        assertThat(api.get("/api/v1/payments/reconciliations/" + run.get("id").asString()).json().get("missingCount").asInt())
                .isOne();
        assertThat(new Api(port, tenant.id(), "payments.record_manual").postCsv("/api/v1/payments/reconciliations", STATEMENT)
                .status()).isEqualTo(403);
    }

    @Test
    void aStatementWithoutTheRequiredColumnsIsRejected() {
        assertThat(api.postCsv("/api/v1/payments/reconciliations", "Date,Amount\n2026-11-17,10\n").status()).isEqualTo(400);
        final Api.Response badAmount = api.postCsv("/api/v1/payments/reconciliations",
                "Receipt No.,Completion Time,Paid In\nQKR1,2026-11-17 10:00:00,ten\n");
        assertThat(badAmount.status()).isEqualTo(400);
        assertThat(badAmount.body()).contains("\"field\":\"Paid In\"");
    }

    @Test
    void aMissingTransactionIsInvestigatedWithTransactionStatus() {
        final Api.Response response = api.post("/api/v1/payments/transaction-status", "{\"transactionId\":\"QKR0000003\"}");
        assertThat(response.status()).as(response.body()).isEqualTo(202);
        final String sent = DARAJA.requestsTo("/mpesa/transactionstatus/v1/query").getLast().body();
        assertThat(sent).contains("\"TransactionID\":\"QKR0000003\"").contains("\"SecurityCredential\":\"fake-security-credential\"")
                .contains("/txn-status/result");
    }

    private void manual(final String reference, final long amount, final String receivedAt) {
        final Api.Response response = api.postWithKey("/api/v1/payments/manual", UUID.randomUUID().toString(),
                "{\"customerId\":\"" + customer + "\",\"amount\":{\"amount\":" + amount + ",\"currency\":\"KES\"},"
                        + "\"channel\":\"MPESA_PAYBILL\",\"reference\":\"" + reference + "\",\"receivedAt\":\"" + receivedAt + "\"}");
        assertThat(response.status()).as(response.body()).isEqualTo(201);
    }
}
