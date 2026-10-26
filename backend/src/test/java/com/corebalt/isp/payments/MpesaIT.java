package com.corebalt.isp.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.corebalt.isp.payments.application.StkService;
import com.corebalt.isp.shared.tenant.TenantContext;
import com.corebalt.isp.support.Api;
import com.corebalt.isp.support.Catalog;
import com.corebalt.isp.support.IntegrationTest;
import com.corebalt.isp.support.OwnerSql;
import com.corebalt.isp.support.TestTenants;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * M-Pesa against the fake Daraja with production-shaped callbacks (docs/05): store-then-ack (P2),
 * one payment per TransID (P1), the P3 cascade with masked and hashed numbers, the suspense queue,
 * STK results and P6. Every number, name and credential is a fake.
 */
class MpesaIT extends IntegrationTest {

    static final AtomicInteger SEQUENCE = new AtomicInteger();

    @Value("${local.server.port}")
    int port;

    @Autowired JdbcClient jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired StkService stk;

    final HttpClient http = HttpClient.newHttpClient();

    TestTenants.Created tenant;
    Api api;
    String token;
    String wanjiku;
    String wanjikuNumber;
    String john;

    @BeforeEach
    void tenantWithMpesa() {
        tenant = TestTenants.createWithCode(jdbc, tx);
        api = new Api(port, tenant.id());
        wanjiku = Catalog.customer(api, "Wanjiku Kamau", "0712345678");
        wanjikuNumber = api.get("/api/v1/customers/" + wanjiku).json().get("number").asString();
        john = Catalog.customer(api, "John Otieno", "0722000126");
        final Api.Response configured = api.put("/api/v1/settings/mpesa", """
                {"shortcode":"600984","shortcodeType":"PAYBILL","sandbox":true,
                 "consumerKey":"fake-key","consumerSecret":"fake-secret","passkey":"fake-passkey"}
                """);
        assertThat(configured.status()).as(configured.body()).isEqualTo(200);
        final String confirmation = configured.json().get("callbackUrls").get("c2bConfirmation").asString();
        token = confirmation.replaceAll(".*/callbacks/mpesa/([^/]+)/.*", "$1");
        assertThat(configured.body()).doesNotContain("fake-secret").doesNotContain("fake-passkey");
    }

    @Test
    void P2_P3_aConfirmationIsStoredAcknowledgedAndMatchedByExactReference() throws Exception {
        final String txn = txn();
        final HttpResponse<String> ack = callback("c2b/confirmation", fixture("c2b-v2-confirmation-masked.json",
                Map.of("TRANS_ID", txn, "AMOUNT", "2500.00", "REF", wanjikuNumber, "MSISDN", "2547 ***** 126")));
        assertThat(ack.statusCode()).isEqualTo(200);
        assertThat(ack.body()).contains("\"ResultCode\":0");
        final Map<String, Object> payment = awaitPayment(txn);
        assertThat(payment).containsEntry("status", "MATCHED").containsEntry("customer_id", UUID.fromString(wanjiku))
                .containsEntry("amount_minor", 250_000L).containsEntry("payer_name", "JOHN");
        assertThat(events("PaymentReceived", payment.get("id"))).isOne();
    }

    @Test
    void P3_aSloppyReferenceIsNormalised() throws Exception {
        final String txn = txn();
        final String sloppy = " " + tenant.code().toLowerCase() + "-" + Integer.parseInt(wanjikuNumber.substring(tenant.code().length())) + " ";
        callback("c2b/confirmation", fixture("c2b-v2-confirmation-masked.json",
                Map.of("TRANS_ID", txn, "AMOUNT", "100", "REF", sloppy, "MSISDN", "2547 ***** 126")));
        assertThat(awaitPayment(txn)).containsEntry("status", "MATCHED").containsEntry("customer_id", UUID.fromString(wanjiku));
    }

    /**
     * ADR 0014: the reference a customer used in the old system is matched exactly, before the
     * normalised reference, so a digits-only old reference never turns into somebody else's number.
     */
    @Test
    void P3_anOldSystemReferenceMatchesExactlyBeforeNormalisation() throws Exception {
        // John's old account number is "1": normalised, that is Wanjiku's number (CB000001).
        final Api.Response updated = api.put("/api/v1/customers/" + john, """
                {"name":"John Otieno","phone":"0722000126","legacyAccountRef":" 1 "}
                """);
        assertThat(updated.status()).as(updated.body()).isEqualTo(200);
        assertThat(updated.json().get("legacyAccountRef").asString()).isEqualTo("1");
        final String txn = txn();
        callback("c2b/confirmation", fixture("c2b-v2-confirmation-masked.json",
                Map.of("TRANS_ID", txn, "AMOUNT", "100", "REF", " 1 ", "MSISDN", "2547 ***** 126")));
        assertThat(awaitPayment(txn)).containsEntry("status", "MATCHED").containsEntry("customer_id", UUID.fromString(john));

        final String lower = txn();
        api.put("/api/v1/customers/" + john, """
                {"name":"John Otieno","phone":"0722000126","legacyAccountRef":"hse-4"}
                """);
        callback("c2b/confirmation", fixture("c2b-v2-confirmation-masked.json",
                Map.of("TRANS_ID", lower, "AMOUNT", "100", "REF", "Hse-4", "MSISDN", "2547 ***** 126")));
        assertThat(awaitPayment(lower)).as("compared upper-cased").containsEntry("customer_id", UUID.fromString(john));
    }

    @Test
    void P3_aHashedNumberMatchesExactlyOneCustomer() throws Exception {
        final String txn = txn();
        callback("c2b/confirmation", fixture("c2b-v1-confirmation-hashed.json",
                Map.of("TRANS_ID", txn, "AMOUNT", "500.00", "REF", "WRONGREF", "MSISDN", sha256("254712345678").toUpperCase())));
        final Map<String, Object> payment = awaitPayment(txn);
        assertThat(payment).containsEntry("status", "MATCHED").containsEntry("customer_id", UUID.fromString(wanjiku))
                .containsEntry("payer_name", "JOHN DOE");
    }

    @Test
    void P3_aMaskedNumberIsNeverMatchedButStaffGetAShortlistAndAssign() throws Exception {
        final String txn = txn();
        callback("c2b/confirmation", fixture("c2b-v2-confirmation-masked.json",
                Map.of("TRANS_ID", txn, "AMOUNT", "1500.00", "REF", "HOUSE 4", "MSISDN", "2547 ***** 126")));
        final Map<String, Object> payment = awaitPayment(txn);
        assertThat(payment).containsEntry("status", "UNMATCHED");
        assertThat(events("PaymentUnmatched", payment.get("id"))).isOne();
        assertThat(events("PaymentReceived", payment.get("id"))).isZero();

        final JsonNode suspense = api.get("/api/v1/payments/suspense").json().get("items").get(0);
        assertThat(suspense.get("reference").asString()).isEqualTo(txn);
        assertThat(suspense.get("payerNumber").asString()).isEqualTo("2547*****126");
        assertThat(suspense.get("likelyPayers")).hasSize(1);
        assertThat(suspense.get("likelyPayers").get(0).get("id").asString()).isEqualTo(john);

        final String path = "/api/v1/payments/" + payment.get("id") + "/assign";
        assertThat(new Api(port, tenant.id(), "payments.record_manual")
                        .postWithKey(path, "a1", "{\"customerId\":\"" + john + "\"}").status())
                .isEqualTo(403);
        final Api.Response assigned = api.postWithKey(path, "a2", "{\"customerId\":\"" + john + "\"}");
        assertThat(assigned.status()).isEqualTo(200);
        assertThat(assigned.json().get("status").asString()).isEqualTo("MATCHED");
        assertThat(api.postWithKey(path, "a3", "{\"customerId\":\"" + wanjiku + "\"}").status()).isEqualTo(409);
        assertThat(events("PaymentReceived", payment.get("id"))).isOne();
        assertThat(OwnerSql.client().sql("select count(*) from identity_audit_log where action = 'payments.assigned' and target_id = :id")
                .param("id", payment.get("id")).query(Long.class).single()).isOne();
    }

    @Test
    void P1_theSameTransIdTwiceIsOnePayment() throws Exception {
        final String txn = txn();
        final String body = fixture("c2b-v2-confirmation-masked.json",
                Map.of("TRANS_ID", txn, "AMOUNT", "2500.00", "REF", wanjikuNumber, "MSISDN", "2547 ***** 126"));
        callback("c2b/confirmation", body);
        callback("c2b/confirmation", body);
        awaitPayment(txn);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(OwnerSql.client()
                .sql("select count(*) from payments_mpesa_callback where tenant_id = :t and processed_at is not null")
                .param("t", tenant.id()).query(Long.class).single()).isEqualTo(2));
        assertThat(OwnerSql.client().sql("select count(*) from payments_payment where provider_txn_id = :txn")
                .param("txn", txn).query(Long.class).single()).isOne();
    }

    @Test
    void P2_aMalformedBodyIsAcknowledgedStoredAndSurfaces() throws Exception {
        final HttpResponse<String> ack = callback("c2b/confirmation", "{\"TransID\": \"QK1\", \"TransAmount\": ");
        assertThat(ack.statusCode()).isEqualTo(200);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(OwnerSql.client()
                .sql("select last_error from payments_mpesa_callback where tenant_id = :t and processed_at is not null")
                .param("t", tenant.id()).query(String.class).list()).containsExactly("malformed body: not JSON"));
    }

    @Test
    void validationAcceptsAndUnknownTokensAre404() throws Exception {
        final HttpResponse<String> validation = callback("c2b/validation",
                fixture("c2b-validation.json", Map.of("TRANS_ID", txn(), "AMOUNT", "10.00", "REF", "ANY")));
        assertThat(validation.statusCode()).isEqualTo(200);
        assertThat(validation.body()).contains("\"ResultCode\":0");
        final HttpResponse<String> unknown = http.send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/callbacks/mpesa/not-a-real-token-at-all-xyz/c2b/confirmation"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{}")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(unknown.statusCode()).isEqualTo(404);
    }

    @Test
    void stkSuccessIsMatchedToTheRequestingCustomer() throws Exception {
        final String checkout = pushReturns();
        final JsonNode request = push(wanjiku, 100_000);
        assertThat(request.get("status").asString()).isEqualTo("PENDING");
        final var sent = DARAJA.requestsTo("/mpesa/stkpush/v1/processrequest").getLast().body();
        assertThat(sent).contains("\"PhoneNumber\":\"254712345678\"").contains("\"AccountReference\":\"" + wanjikuNumber + "\"")
                .contains("\"Amount\":1000").contains("/callbacks/mpesa/" + token + "/stk");

        final String txn = txn();
        callback("stk", fixture("stk-callback-success.json", Map.of("CHECKOUT", checkout, "AMOUNT", "1000", "TRANS_ID", txn)));
        final Map<String, Object> payment = awaitPayment(txn);
        assertThat(payment).containsEntry("channel", "MPESA_STK").containsEntry("status", "MATCHED")
                .containsEntry("customer_id", UUID.fromString(wanjiku));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(stkStatus(request)).isEqualTo("COMPLETED"));
    }

    @Test
    void P6_theRequestIsCommittedBeforeDarajaIsCalledAndTheCheckoutIdRightAfter() throws Exception {
        final String checkout = pushReturns();
        final java.util.concurrent.atomic.AtomicLong pendingSeenByDaraja = new java.util.concurrent.atomic.AtomicLong(-1);
        DARAJA.beforeNextAnswer("/mpesa/stkpush/v1/processrequest", () -> pendingSeenByDaraja.set(OwnerSql.client()
                .sql("select count(*) from payments_stk_request where tenant_id = :t and status = 'PENDING'")
                .param("t", tenant.id()).query(Long.class).single()));
        push(wanjiku, 100_000);
        assertThat(pendingSeenByDaraja.get()).as("PENDING row visible to another connection during the push").isOne();
        // A result callback that arrives at once is matched to the request, not dropped as unknown.
        final String txn = txn();
        callback("stk", fixture("stk-callback-success.json", Map.of("CHECKOUT", checkout, "AMOUNT", "1000", "TRANS_ID", txn)));
        assertThat(awaitPayment(txn)).containsEntry("channel", "MPESA_STK").containsEntry("customer_id", UUID.fromString(wanjiku));
    }

    @Test
    void stkFailuresAreRecordedWithTheirCode() throws Exception {
        for (final String[] failure : new String[][] {
                {"1032", "Request cancelled by user"}, {"1037", "DS timeout user cannot be reached"}, {"2001", "The initiator information is invalid."}}) {
            final String checkout = pushReturns();
            final JsonNode request = push(wanjiku, 50_000);
            callback("stk", fixture("stk-callback-failed.json",
                    Map.of("CHECKOUT", checkout, "RESULT_CODE", failure[0], "RESULT_DESC", failure[1])));
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                final JsonNode now = api.get("/api/v1/payments/stk/" + request.get("id").asString()).json();
                assertThat(now.get("status").asString()).isEqualTo("FAILED");
                assertThat(now.get("resultCode").asString()).isEqualTo(failure[0]);
            });
        }
    }

    @Test
    void P1_c2bFirstThenStkResultIsOnePaymentLinkedToTheRequest() throws Exception {
        final String checkout = pushReturns();
        final JsonNode request = push(wanjiku, 100_000);
        final String txn = txn();
        callback("c2b/confirmation", fixture("c2b-v2-confirmation-masked.json",
                Map.of("TRANS_ID", txn, "AMOUNT", "1000.00", "REF", wanjikuNumber, "MSISDN", "2547 ***** 126")));
        final Map<String, Object> payment = awaitPayment(txn);
        callback("stk", fixture("stk-callback-success.json", Map.of("CHECKOUT", checkout, "AMOUNT", "1000", "TRANS_ID", txn)));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(api.get("/api/v1/payments/stk/" + request.get("id").asString()).json().get("paymentId").asString())
                        .isEqualTo(payment.get("id").toString()));
        assertThat(OwnerSql.client().sql("select count(*) from payments_payment where provider_txn_id = :txn")
                .param("txn", txn).query(Long.class).single()).isOne();
    }

    @Test
    void P1_stkResultFirstThenC2bIsOnePayment() throws Exception {
        final String checkout = pushReturns();
        push(wanjiku, 100_000);
        final String txn = txn();
        callback("stk", fixture("stk-callback-success.json", Map.of("CHECKOUT", checkout, "AMOUNT", "1000", "TRANS_ID", txn)));
        awaitPayment(txn);
        callback("c2b/confirmation", fixture("c2b-v2-confirmation-masked.json",
                Map.of("TRANS_ID", txn, "AMOUNT", "1000.00", "REF", wanjikuNumber, "MSISDN", "2547 ***** 126")));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(OwnerSql.client()
                .sql("select count(*) from payments_mpesa_callback where tenant_id = :t and processed_at is not null")
                .param("t", tenant.id()).query(Long.class).single()).isEqualTo(2));
        assertThat(OwnerSql.client().sql("select count(*) from payments_payment where provider_txn_id = :txn")
                .param("txn", txn).query(Long.class).single()).isOne();
    }

    @Test
    void P6_queriedAfterTwoMinutesTimedOutAfterFiveAndALateSuccessStillCredits() throws Exception {
        final String checkout = pushReturns();
        final JsonNode request = push(wanjiku, 100_000);
        backdate(request, "3 minutes");
        DARAJA.respond("/mpesa/stkpushquery/v1/query", 500,
                "{\"requestId\":\"1\",\"errorCode\":\"500.001.1001\",\"errorMessage\":\"The transaction is being processed\"}");
        TenantContext.runAsTenant(tenant.id(), stk::followUpPending);
        assertThat(stkStatus(request)).isEqualTo("PENDING");
        assertThat(DARAJA.requestsTo("/mpesa/stkpushquery/v1/query").getLast().body()).contains(checkout);

        backdate(request, "6 minutes");
        TenantContext.runAsTenant(tenant.id(), stk::followUpPending);
        assertThat(stkStatus(request)).isEqualTo("TIMED_OUT");

        final String txn = txn();
        callback("stk", fixture("stk-callback-success.json", Map.of("CHECKOUT", checkout, "AMOUNT", "1000", "TRANS_ID", txn)));
        assertThat(awaitPayment(txn)).containsEntry("status", "MATCHED");
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(stkStatus(request)).isEqualTo("COMPLETED"));
    }

    @Test
    void P6_aCallbackThatArrivesWhileTheQueryIsInFlightWins() throws Exception {
        final String checkout = pushReturns();
        final JsonNode request = push(wanjiku, 100_000);
        backdate(request, "3 minutes");
        final String txn = txn();
        DARAJA.respond("/mpesa/stkpushquery/v1/query", 200,
                "{\"ResponseCode\":\"0\",\"ResultCode\":\"1032\",\"ResultDesc\":\"Request cancelled by user\"}");
        DARAJA.beforeNextAnswer("/mpesa/stkpushquery/v1/query", () -> {
            try {
                callback("stk", fixture("stk-callback-success.json", Map.of("CHECKOUT", checkout, "AMOUNT", "1000", "TRANS_ID", txn)));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(stkStatus(request)).isEqualTo("COMPLETED"));
        });
        TenantContext.runAsTenant(tenant.id(), stk::followUpPending);
        assertThat(stkStatus(request)).isEqualTo("COMPLETED");
    }

    @Test
    void aCompletedTransactionStatusResultBecomesAPayment() throws Exception {
        final String txn = txn();
        callback("txn-status/result", fixture("txn-status-result.json",
                Map.of("TRANS_ID", txn, "AMOUNT", "800", "MSISDN", "254712345678")));
        assertThat(awaitPayment(txn)).containsEntry("status", "MATCHED").containsEntry("customer_id", UUID.fromString(wanjiku));
    }

    private JsonNode push(final String customerId, final long minor) {
        final Api.Response response = api.postWithKey("/api/v1/payments/stk", UUID.randomUUID().toString(),
                "{\"customerId\":\"" + customerId + "\",\"amount\":{\"amount\":" + minor + ",\"currency\":\"KES\"}}");
        assertThat(response.status()).as(response.body()).isEqualTo(202);
        return response.json();
    }

    /** The next STK Push gets a fresh CheckoutRequestID. */
    private String pushReturns() {
        final String checkout = "ws_CO_" + UUID.randomUUID().toString().replace("-", "");
        DARAJA.respond("/mpesa/stkpush/v1/processrequest", 200, """
                {"MerchantRequestID":"29115-1","CheckoutRequestID":"%s","ResponseCode":"0",
                 "ResponseDescription":"Success. Request accepted for processing","CustomerMessage":"Success"}
                """.formatted(checkout));
        return checkout;
    }

    private String stkStatus(final JsonNode request) {
        return api.get("/api/v1/payments/stk/" + request.get("id").asString()).json().get("status").asString();
    }

    private static void backdate(final JsonNode request, final String interval) {
        OwnerSql.client().sql("update payments_stk_request set created_at = now() - cast(:i as interval) where id = cast(:id as uuid)")
                .param("i", interval).param("id", request.get("id").asString()).update();
    }

    private HttpResponse<String> callback(final String path, final String body) throws IOException, InterruptedException {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/callbacks/mpesa/" + token + "/" + path))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static Map<String, Object> awaitPayment(final String txn) {
        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(200)).until(() -> OwnerSql.client()
                .sql("select count(*) from payments_payment where provider_txn_id = :txn").param("txn", txn)
                .query(Long.class).single() == 1);
        return OwnerSql.client().sql("""
                        select id, status, customer_id, amount_minor, payer_name, channel
                        from payments_payment where provider_txn_id = :txn
                        """)
                .param("txn", txn).query().singleRow();
    }

    private static long events(final String type, final Object paymentId) {
        return OwnerSql.client()
                .sql("select count(*) from outbox_event where event_type = :type and payload ->> 'paymentId' = :id")
                .param("type", type).param("id", paymentId.toString()).query(Long.class).single();
    }

    private static String txn() {
        return "QK" + String.format("%08d", SEQUENCE.incrementAndGet()) + UUID.randomUUID().toString().substring(0, 4).toUpperCase();
    }

    private static String fixture(final String name, final Map<String, String> values) {
        try (InputStream in = MpesaIT.class.getResourceAsStream("/mpesa/" + name)) {
            String body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            for (final Map.Entry<String, String> value : values.entrySet()) {
                body = body.replace("${" + value.getKey() + "}", value.getValue());
            }
            return body;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String sha256(final String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }
}
