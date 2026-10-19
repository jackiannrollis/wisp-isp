package com.corebalt.isp.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.corebalt.isp.notifications.application.MessageComposer;
import com.corebalt.isp.notifications.application.SmsSender;
import com.corebalt.isp.shared.outbox.OutboxRelay;
import com.corebalt.isp.shared.tenant.TenantContext;
import com.corebalt.isp.subscriptions.api.SubscriptionReminderDue;
import com.corebalt.isp.support.Api;
import com.corebalt.isp.support.Catalog;
import com.corebalt.isp.support.FakeAfricasTalking;
import com.corebalt.isp.support.IntegrationTest;
import com.corebalt.isp.support.OwnerSql;
import com.corebalt.isp.support.TestTenants;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
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
 * SMS end to end against the fake Africa's Talking (docs/04 Reminders, docs/05, M1-M3): the one
 * payment message and its variants, the sending window, the answer-to-behaviour mapping, delivery
 * reports and the template editor. Numbers, names and keys are fakes.
 */
class NotificationsIT extends IntegrationTest {

    static final AtomicInteger PHONES = new AtomicInteger(100);

    @Value("${local.server.port}")
    int port;

    @Autowired JdbcClient jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired OutboxRelay relay;
    @Autowired SmsSender sender;
    @Autowired MessageComposer composer;

    TestTenants.Created tenant;
    Api api;
    String packageId;
    String routerId;
    String customerId;
    String phone;
    String number;
    String deliveryUrl;
    String home20;

    @BeforeEach
    void tenantWithSms() {
        tenant = TestTenants.createWithCode(jdbc, tx);
        api = new Api(port, tenant.id());
        AT.registerSender("LABISP");
        final Api.Response settings = api.put("/api/v1/settings/notifications", """
                {"atUsername":"sandbox","senderId":"LABISP","sandbox":true,"apiKey":"%s","windowStart":"00:00","windowEnd":"23:59"}
                """.formatted(FakeAfricasTalking.API_KEY));
        assertThat(settings.status()).as(settings.body()).isEqualTo(200);
        assertThat(settings.body()).doesNotContain(FakeAfricasTalking.API_KEY);
        deliveryUrl = settings.json().get("deliveryReportUrl").asString().replaceAll("^https?://[^/]+", "http://localhost:" + port);
        packageId = Catalog.pppoePackage(api, "Home 10");
        routerId = Catalog.router(api, "Utawala-01").json().get("router").get("id").asString();
        phone = nextPhone();
        customerId = Catalog.customer(api, "Wanjiku Kamau", "0" + phone.substring(4));
        number = api.get("/api/v1/customers/" + customerId).json().get("number").asString();
    }

    @Test
    void M1_onePaymentIsOneSmsWithTheReceiptAndTheNewExpiry() {
        final String line = openLine();
        final String paymentId = pay("CASH-N1", 250_000);
        final Map<String, Object> sms = awaitMessage("PAYMENT_RECEIVED:" + paymentId);
        assertThat(sms.get("template_key")).isEqualTo("PAYMENT_RECEIVED_ACTIVATED");
        assertThat((String) sms.get("body")).startsWith("KES 2,500 received (" + tenant.code() + "-R000001). Home 10 is active until ")
                .endsWith("Welcome, Wanjiku!");
        assertThat(sms.get("recipient")).isEqualTo(phone);

        assertThat(TenantContext.callAsTenant(tenant.id(), sender::sendDue)).isOne();
        final FakeAfricasTalking.Recorded sent = AT.requestsTo(phone).getLast();
        assertThat(sent.form()).containsEntry("from", "LABISP").containsEntry("to", phone).containsEntry("username", "sandbox");
        assertThat(message("PAYMENT_RECEIVED:" + paymentId).get("status")).isEqualTo("SENT");
        assertThat(OwnerSql.client().sql("select count(*) from outbox_event where event_type = 'CustomerNotified' and tenant_id = :t")
                .param("t", tenant.id()).query(Long.class).single()).isOne();
        assertThat(OwnerSql.client().sql("select count(*) from notifications_notification where tenant_id = :t")
                .param("t", tenant.id()).query(Long.class).single()).as("one SMS for one payment").isOne();
        assertThat(api.get("/api/v1/subscriptions/" + line).json().get("state").asString()).isEqualTo("ACTIVE");
    }

    @Test
    void aRestoringPaymentTellsTheCustomerWhatToDoIfTheInternetIsNotBack() {
        final String line = openLine();
        pay("CASH-N2a", 250_000);
        awaitMessageCount(1);
        OwnerSql.client().sql("update subscriptions_subscription set expires_at = now() - interval '10 days' where id = cast(:id as uuid)")
                .param("id", line).update();
        final String paymentId = pay("CASH-N2b", 250_000);
        final Map<String, Object> sms = awaitMessage("PAYMENT_RECEIVED:" + paymentId);
        assertThat(sms.get("template_key")).isEqualTo("PAYMENT_RECEIVED_RESTORED");
        assertThat((String) sms.get("body")).contains("Internet is back within a minute; if not, restart your router.");
    }

    @Test
    void aPartialPaymentSaysHowMuchMoreRenewsTheLine() {
        openLine();
        OwnerSql.client().sql("insert into payments_mpesa_config (tenant_id, shortcode, shortcode_type, sandbox, callback_token_hash) "
                + "values (:t, '600984', 'PAYBILL', true, :h)").param("t", tenant.id()).param("h", UUID.randomUUID().toString()).update();
        final String paymentId = pay("CASH-N3", 100_000);
        final Map<String, Object> sms = awaitMessage("PAYMENT_RECEIVED:" + paymentId);
        assertThat(sms.get("template_key")).isEqualTo("PAYMENT_RECEIVED_PARTIAL");
        assertThat((String) sms.get("body"))
                .isEqualTo("KES 1,000 received (" + tenant.code() + "-R000001). Pay KES 1,500 more to Paybill 600984, account "
                        + number + " to renew Home 10.");
    }

    @Test
    void onePaymentRenewingTwoLinesListsThemBoth() {
        openLine();
        openLine();
        final String paymentId = pay("CASH-N4", 500_000);
        final Map<String, Object> sms = awaitMessage("PAYMENT_RECEIVED:" + paymentId);
        assertThat(sms.get("template_key")).isEqualTo("PAYMENT_RECEIVED_MULTI");
        assertThat((String) sms.get("body")).contains(number + " to ").contains(number + "-2 to ");
    }

    @Test
    void M1_M2_remindersAreQueuedOnceAndWaitForTheWindow() {
        final String line = openLine();
        // A window that has just closed in Nairobi: the reminder waits for tomorrow's start.
        final LocalTime nowLocal = LocalTime.now(ZoneId.of("Africa/Nairobi"));
        final LocalTime start = nowLocal.plusMinutes(10).isAfter(nowLocal) ? nowLocal.plusMinutes(10) : LocalTime.of(0, 0);
        final LocalTime end = start.plusMinutes(5).isAfter(start) ? start.plusMinutes(5) : LocalTime.of(23, 59);
        api.put("/api/v1/settings/notifications", """
                {"atUsername":"sandbox","senderId":"LABISP","sandbox":true,"windowStart":"%s","windowEnd":"%s"}
                """.formatted(start.toString().substring(0, 5), end.toString().substring(0, 5)));
        final SubscriptionReminderDue reminder = new SubscriptionReminderDue(UUID.randomUUID(), tenant.id(), Instant.now(),
                UUID.fromString(line), UUID.fromString(customerId), "EXPIRING_3D", Instant.now().plus(Duration.ofDays(3)),
                "EXPIRING_3D:" + line + ":x", false);
        TenantContext.runAsTenant(tenant.id(), () -> composer.reminder(reminder));
        TenantContext.runAsTenant(tenant.id(), () -> composer.reminder(reminder));
        final Map<String, Object> queued = message("EXPIRING_3D:" + line + ":x");
        assertThat(OwnerSql.client().sql("select count(*) from notifications_notification where tenant_id = :t")
                .param("t", tenant.id()).query(Long.class).single()).isOne();
        assertThat(((java.sql.Timestamp) queued.get("scheduled_for")).toInstant()).isAfter(Instant.now());
        assertThat(TenantContext.callAsTenant(tenant.id(), sender::sendDue)).isZero();
    }

    @Test
    void invalidAndBlacklistedNumbersFailForGoodAndAreFlagged() {
        AT.answerFor(phone, 403, "InvalidPhoneNumber");
        final String line = openLine();
        packageChange(line);
        TenantContext.runAsTenant(tenant.id(), sender::sendDue);
        final Map<String, Object> failed = latest();
        assertThat(failed).containsEntry("status", "FAILED").containsEntry("failure_reason", "403 InvalidPhoneNumber");
        final JsonNode flags = api.get("/api/v1/notifications/phone-flags").json();
        assertThat(flags.get(0).get("phone").asString()).isEqualTo(phone);
        assertThat(flags.get(0).get("status").asString()).isEqualTo("INVALID_NUMBER");
        // Never retried, and the next message to that number is not even attempted.
        packageChange(line);
        TenantContext.runAsTenant(tenant.id(), sender::sendDue);
        assertThat(latest()).containsEntry("status", "SUPPRESSED");
        assertThat(AT.requestsTo(phone)).hasSize(1);
        AT.reset(phone);
    }

    @Test
    void insufficientCreditWaitsHourlyAlertsAndClearsOnTheNextSuccess() {
        AT.answerFor(phone, 405, "InsufficientBalance");
        final String line = openLine();
        packageChange(line);
        TenantContext.runAsTenant(tenant.id(), sender::sendDue);
        final Map<String, Object> waiting = latest();
        assertThat(waiting).containsEntry("status", "QUEUED_RETRY");
        assertThat(((java.sql.Timestamp) waiting.get("next_attempt_at")).toInstant()).isAfter(Instant.now().plus(Duration.ofMinutes(55)));
        assertThat(api.get("/api/v1/notifications/alerts").json().get(0).get("kind").asString()).isEqualTo("SMS_CREDIT_EXHAUSTED");

        AT.reset(phone);
        OwnerSql.client().sql("update notifications_notification set next_attempt_at = now() where tenant_id = :t").param("t", tenant.id()).update();
        TenantContext.runAsTenant(tenant.id(), sender::sendDue);
        assertThat(latest()).containsEntry("status", "SENT");
        assertThat(api.get("/api/v1/notifications/alerts").json()).isEmpty();
    }

    @Test
    void serverErrorsAreRetriedWithBackoffThenDeadLettered() {
        AT.failFor(phone, 502);
        final String line = openLine();
        packageChange(line);
        for (int attempt = 1; attempt <= 4; attempt++) {
            OwnerSql.client().sql("update notifications_notification set next_attempt_at = now() where tenant_id = :t").param("t", tenant.id()).update();
            TenantContext.runAsTenant(tenant.id(), sender::sendDue);
        }
        assertThat(latest()).containsEntry("status", "DEAD").containsEntry("attempts", 4);
        assertThat(api.get("/api/v1/notifications/alerts").body()).contains("SMS_DEAD_LETTERED");
        assertThat(AT.requestsTo(phone)).hasSize(4);
        AT.reset(phone);
    }

    @Test
    void anUnregisteredSenderIdIsATenantProblem() {
        api.put("/api/v1/settings/notifications", "{\"atUsername\":\"sandbox\",\"senderId\":\"NOTREG\",\"sandbox\":true}");
        packageChange(openLine());
        TenantContext.runAsTenant(tenant.id(), sender::sendDue);
        assertThat(latest()).containsEntry("status", "QUEUED_RETRY");
        assertThat(api.get("/api/v1/notifications/alerts").body()).contains("SENDER_ID_INVALID");
    }

    @Test
    void deliveryReportsMoveTheStatus() throws Exception {
        packageChange(openLine());
        TenantContext.runAsTenant(tenant.id(), sender::sendDue);
        final String messageId = (String) latest().get("provider_message_id");
        assertThat(report(deliveryUrl, messageId, "Buffered", null)).isEqualTo(200);
        assertThat(latest()).containsEntry("status", "SENT").containsEntry("provider_status", "Buffered");
        report(deliveryUrl, messageId, "Success", null);
        assertThat(latest()).containsEntry("status", "DELIVERED");
        assertThat(report(deliveryUrl, "ATXid_unknown", "Failed", "UnknownError")).isEqualTo(200);
        assertThat(report(deliveryUrl.replaceAll("/callbacks/sms/[^/]+/", "/callbacks/sms/not-a-real-token-at-all-xyz/"),
                messageId, "Failed", "x")).isEqualTo(404);
    }

    @Test
    void aFailedDeliveryKeepsTheReason() throws Exception {
        packageChange(openLine());
        TenantContext.runAsTenant(tenant.id(), sender::sendDue);
        report(deliveryUrl, (String) latest().get("provider_message_id"), "Rejected", "DeliveryFailure");
        assertThat(latest()).containsEntry("status", "FAILED").containsEntry("failure_reason", "Rejected: DeliveryFailure");
    }

    @Test
    void M3_theTemplateEditorRefusesUnknownPlaceholdersAndCountsParts() {
        final Api.Response refused = api.put("/api/v1/settings/notifications/templates/RESTRICTED", "{\"body\":\"Your PIN {pin}\"}");
        assertThat(refused.status()).isEqualTo(400);
        assertThat(refused.body()).contains("pin");
        final JsonNode preview = api.post("/api/v1/settings/notifications/templates/RESTRICTED/preview",
                "{\"body\":\"" + "Habari {name}, ".repeat(12) + "\"}").json();
        assertThat(preview.get("segments").asInt()).isEqualTo(2);
        assertThat(preview.get("sample").asString()).startsWith("Habari Wanjiku,");

        api.put("/api/v1/settings/notifications/templates/PACKAGE_CHANGED_NEXT", "{\"body\":\"Sasa {name}, {package} itaanza karibuni.\"}");
        packageChange(openLine());
        assertThat((String) latest().get("body")).isEqualTo("Sasa Wanjiku, Home 20 itaanza karibuni.");
        final JsonNode templates = api.get("/api/v1/settings/notifications/templates").json();
        assertThat(templates.toString()).contains("\"key\":\"PACKAGE_CHANGED_NEXT\"").contains("\"custom\":true");
        assertThat(new Api(port, tenant.id(), "billing.adjust").get("/api/v1/settings/notifications/templates").status()).isEqualTo(403);
    }

    @Test
    void anUnrecognisedReferenceIsAnsweredOnlyWhenTheNumberIsPlain() throws Exception {
        final String token = api.put("/api/v1/settings/mpesa", """
                {"shortcode":"600984","shortcodeType":"PAYBILL","sandbox":true,"consumerKey":"k","consumerSecret":"s","passkey":"p"}
                """).json().get("callbackUrls").get("c2bConfirmation").asString().replaceAll(".*/callbacks/mpesa/([^/]+)/.*", "$1");
        final String payer = nextPhone();
        confirmation(token, "QKN" + PHONES.get() + "A", "HOUSE 4", payer.substring(1));
        confirmation(token, "QKN" + PHONES.get() + "B", "HOUSE 4", "2547 ***** 126");
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(300)).untilAsserted(() -> {
            TenantContext.callAsTenant(tenant.id(), relay::relayTenant);
            assertThat(OwnerSql.client().sql("select count(*) from notifications_notification where tenant_id = :t and template_key = 'PAYMENT_UNMATCHED'")
                    .param("t", tenant.id()).query(Long.class).single()).isOne();
        });
        final Map<String, Object> sms = latest();
        assertThat(sms.get("recipient")).isEqualTo(payer);
        assertThat(sms.get("customer_id")).isNull();
        assertThat((String) sms.get("body")).contains("account HOUSE 4").contains("starts with " + tenant.code());
    }

    private void confirmation(final String token, final String txn, final String ref, final String msisdn) throws Exception {
        final String body = """
                {"TransactionType":"Pay Bill","TransID":"%s","TransTime":"20261117143012","TransAmount":"700.00",
                 "BusinessShortCode":"600984","BillRefNumber":"%s","MSISDN":"%s","FirstName":"JOHN"}
                """.formatted(txn, ref, msisdn);
        HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/callbacks/mpesa/" + token + "/c2b/confirmation"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private String openLine() {
        return api.post("/api/v1/subscriptions",
                        "{\"customerId\":\"" + customerId + "\",\"packageId\":\"" + packageId + "\",\"routerId\":\"" + routerId + "\"}")
                .json().get("subscription").get("id").asString();
    }

    /** An immediate message (PACKAGE_CHANGED_NEXT) to the customer, without money. */
    private void packageChange(final String line) {
        if (home20 == null) {
            home20 = api.post("/api/v1/packages", """
                    {"name":"Home 20","downloadKbps":20000,"uploadKbps":10000,"durationMinutes":43200,
                     "price":{"amount":350000,"currency":"KES"}}
                    """).json().get("id").asString();
        }
        final long before = OwnerSql.client().sql("select count(*) from notifications_notification where tenant_id = :t")
                .param("t", tenant.id()).query(Long.class).single();
        final Api.Response changed = api.postWithKey("/api/v1/subscriptions/" + line + "/package", UUID.randomUUID().toString(),
                "{\"packageId\":\"" + home20 + "\",\"timing\":\"NEXT_RENEWAL\"}");
        assertThat(changed.status()).as(changed.body()).isEqualTo(200);
        awaitMessageCount(before + 1);
        // The next change must differ from the pending one, so switch back and forth.
        final String swap = home20;
        home20 = packageId;
        packageId = swap;
    }

    private String pay(final String reference, final long amount) {
        final Api.Response response = api.postWithKey("/api/v1/payments/manual", "pay-" + reference,
                "{\"customerId\":\"" + customerId + "\",\"amount\":{\"amount\":" + amount + ",\"currency\":\"KES\"},"
                        + "\"channel\":\"CASH\",\"reference\":\"" + reference + "\"}");
        assertThat(response.status()).as(response.body()).isEqualTo(201);
        return response.json().get("id").asString();
    }

    private Map<String, Object> awaitMessage(final String dedupeKey) {
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(300)).until(() -> {
            TenantContext.callAsTenant(tenant.id(), relay::relayTenant);
            return OwnerSql.client().sql("select count(*) from notifications_notification where dedupe_key = :k and tenant_id = :t")
                    .param("k", dedupeKey).param("t", tenant.id()).query(Long.class).single() == 1;
        });
        return message(dedupeKey);
    }

    private void awaitMessageCount(final long count) {
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(300)).until(() -> {
            TenantContext.callAsTenant(tenant.id(), relay::relayTenant);
            return OwnerSql.client().sql("select count(*) from notifications_notification where tenant_id = :t")
                    .param("t", tenant.id()).query(Long.class).single() >= count;
        });
    }

    private Map<String, Object> message(final String dedupeKey) {
        return OwnerSql.client().sql("select * from notifications_notification where dedupe_key = :k and tenant_id = :t")
                .param("k", dedupeKey).param("t", tenant.id()).query().singleRow();
    }

    private Map<String, Object> latest() {
        final List<Map<String, Object>> rows = OwnerSql.client()
                .sql("select * from notifications_notification where tenant_id = :t order by created_at desc, id desc limit 1")
                .param("t", tenant.id()).query().listOfRows();
        return rows.getFirst();
    }

    private static int report(final String url, final String id, final String status, final String reason) throws Exception {
        final String form = "id=" + id + "&status=" + status + "&phoneNumber=%2B254700000000&networkCode=63902"
                + (reason == null ? "" : "&failureReason=" + reason) + "&retryCount=0";
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(url))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form)).build(),
                HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    private static String nextPhone() {
        return "+2547" + String.format("%08d", 10_000_000 + PHONES.incrementAndGet());
    }
}
