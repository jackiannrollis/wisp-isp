package com.corebalt.isp.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.corebalt.isp.network.api.NetworkAccessChanged;
import com.corebalt.isp.network.api.SessionReauthorized;
import com.corebalt.isp.reporting.api.Gate1Dto;
import com.corebalt.isp.reporting.application.Gate1Service;
import com.corebalt.isp.shared.id.UuidV7;
import com.corebalt.isp.shared.outbox.OutboxPublisher;
import com.corebalt.isp.shared.outbox.OutboxRelay;
import com.corebalt.isp.shared.security.Tokens;
import com.corebalt.isp.shared.tenant.TenantContext;
import com.corebalt.isp.support.Api;
import com.corebalt.isp.support.Catalog;
import com.corebalt.isp.support.IntegrationTest;
import com.corebalt.isp.support.OwnerSql;
import com.corebalt.isp.support.TestTenants;
import com.corebalt.isp.support.TestTokens;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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
 * Gate 1 measured by the platform (docs/09, ADR 0018): a real C2B callback pays a RESTRICTED line, and
 * the restore is timed from the platform recording the callback (not M-Pesa's TransTime) to its part
 * done: the renewal for a line with no live session, the router answering the Disconnect, or a new
 * session; none within 10 minutes is missed. Only PLATFORM tokens read it.
 */
class Gate1IT extends IntegrationTest {

    private static final AtomicInteger TXN = new AtomicInteger();

    @Value("${local.server.port}")
    int port;

    @Autowired JdbcClient jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired OutboxRelay relay;
    @Autowired OutboxPublisher outbox;
    @Autowired Gate1Service gate1;
    @Autowired Clock clock;

    TestTenants.Created tenant;
    Api api;
    UUID lineId;
    UUID routerId;
    String username;
    String tunnelIp;
    String customerNumber;
    String token;

    /** A line paid once by callback (its activation, not a restore), then expired ten days ago without grace: RESTRICTED. */
    @BeforeEach
    void restrictedLine() throws Exception {
        tenant = TestTenants.createWithCode(jdbc, tx);
        api = new Api(port, tenant.id());
        final String packageId = Catalog.pppoePackage(api, "Home 10");
        final JsonNode router = Catalog.router(api, "Gate-01").json().get("router");
        routerId = UUID.fromString(router.get("id").asString());
        tunnelIp = router.get("tunnelIp").asString();
        final String customerId = Catalog.customer(api, "Akinyi Odhiambo", "0711000222");
        customerNumber = api.get("/api/v1/customers/" + customerId).json().get("number").asString();
        final JsonNode opened = api.post("/api/v1/subscriptions", "{\"customerId\":\"" + customerId + "\",\"packageId\":\""
                + packageId + "\",\"routerId\":\"" + routerId + "\"}").json();
        lineId = UUID.fromString(opened.get("subscription").get("id").asString());
        username = opened.get("subscription").get("pppoeUsername").asString();
        final Api.Response configured = api.put("/api/v1/settings/mpesa", """
                {"shortcode":"600984","shortcodeType":"PAYBILL","sandbox":true,
                 "consumerKey":"fake-key","consumerSecret":"fake-secret","passkey":"fake-passkey"}
                """);
        assertThat(configured.status()).as(configured.body()).isEqualTo(200);
        token = configured.json().get("callbackUrls").get("c2bConfirmation").asString().replaceAll(".*/callbacks/mpesa/([^/]+)/.*", "$1");

        pay();
        awaitCreditsApplied(1);
        OwnerSql.client().sql("update subscriptions_subscription set expires_at = now() - interval '10 days', grace_days = 0 where id = :id")
                .param("id", lineId).update();
    }

    @Test
    void ADR0018_aRestoreIsTimedFromTheCallbackToTheDisconnectTheRouterTook() throws Exception {
        openSession("g1a00001", clock.instant().minus(Duration.ofHours(1)));
        final String paymentId = pay();
        final Map<String, Object> restore = awaitRestore();
        final Instant callbackAt = callbackReceivedAt(paymentId);
        assertThat(((Timestamp) restore.get("callback_at")).toInstant()).isEqualTo(callbackAt);
        assertThat(restore.get("restored_at")).isNull();

        // The router takes the Disconnect two seconds after the renewal; the event goes through the broker.
        final Instant renewedAt = ((Timestamp) restore.get("renewed_at")).toInstant();
        publish(reauthorized(renewedAt.plusSeconds(2), false));
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(300)).until(() -> {
            TenantContext.callAsTenant(tenant.id(), relay::relayTenant);
            return OwnerSql.client().sql("select count(*) from reporting_reauthorization where tenant_id = :t")
                    .param("t", tenant.id()).query(Long.class).single() == 1;
        });
        assertThat(complete()).isOne();

        final Map<String, Object> done = restoreRow();
        assertThat(done.get("restored_by")).isEqualTo("REAUTHORIZED");
        assertThat(((Timestamp) done.get("restored_at")).toInstant()).isEqualTo(renewedAt.plusSeconds(2));

        final JsonNode measured = platformAdmin().get("/api/platform/tenants/" + tenant.id() + "/gate1?days=30").json();
        assertThat(measured.get("restores").get("total").asLong()).isOne();
        assertThat(measured.get("restores").get("completed").asLong()).isOne();
        assertThat(measured.get("restores").get("missed").asLong()).isZero();
        assertThat(measured.get("restores").get("maxSeconds").asLong())
                .isEqualTo((long) Math.ceil(Duration.between(callbackAt, renewedAt.plusSeconds(2)).toNanos() / 1e9));
        // Both callback payments, the activation and the restore, were matched by reference.
        assertThat(measured.get("matching").get("automatic").asLong()).isEqualTo(2);
        assertThat(measured.get("matching").get("automaticPercent").asDouble()).isEqualTo(100.0);
        assertThat(measured.get("targets").get("matchingAbove98").asBoolean()).isTrue();
        assertThat(measured.get("targets").get("noRestoreSlowerThan60s").asBoolean()).isTrue();
        assertThat(measured.get("targets").get("noReconnectsNeedingHands").asBoolean()).isTrue();
    }

    @Test
    void ADR0018_aLineWithNoLiveSessionIsRestoredAtTheRenewal() throws Exception {
        openSession("g1a00002", clock.instant().minus(Duration.ofHours(3)));
        OwnerSql.client().sql("update radacct set acctstoptime = now() - interval '2 hours' where acctsessionid = 'g1a00002' and tenant_id = :t")
                .param("t", tenant.id()).update();
        pay();
        final Map<String, Object> restore = awaitRestore();
        assertThat(complete()).isOne();
        final Map<String, Object> done = restoreRow();
        assertThat(done.get("restored_by")).isEqualTo("NO_SESSION");
        assertThat(done.get("restored_at")).isEqualTo(restore.get("renewed_at"));
    }

    @Test
    void ADR0018_aNewSessionCompletesARestoreTheRouterNeverAnswered() throws Exception {
        openSession("g1a00003", clock.instant().minus(Duration.ofHours(1)));
        pay();
        final Instant renewedAt = ((Timestamp) awaitRestore().get("renewed_at")).toInstant();
        assertThat(complete()).isZero();

        openSession("g1a00004", renewedAt.plusSeconds(5));
        // An answer after the new session started does not move the restore later.
        TenantContext.runAsTenant(tenant.id(), () -> gate1.recordReauthorization(reauthorized(renewedAt.plusSeconds(9), true)));
        assertThat(complete()).isOne();
        final Map<String, Object> done = restoreRow();
        assertThat(done.get("restored_by")).isEqualTo("NEW_SESSION");
        assertThat(((Timestamp) done.get("restored_at")).toInstant()).isEqualTo(renewedAt.plusSeconds(5));
    }

    @Test
    void ADR0018_noAnswerInTenMinutesIsMissed_aSlowAnswerFailsTheTarget() throws Exception {
        openSession("g1a00005", clock.instant().minus(Duration.ofHours(1)));
        pay();
        awaitRestore();
        // The payment was eleven minutes ago and nothing answered since.
        OwnerSql.client().sql("""
                        update reporting_restore
                        set callback_at = callback_at - interval '11 minutes', renewed_at = renewed_at - interval '11 minutes'
                        where tenant_id = :t
                        """)
                .param("t", tenant.id()).update();
        assertThat(complete()).isZero();
        Gate1Dto measured = measure();
        assertThat(measured.restores().missed()).isOne();
        assertThat(measured.restores().pending()).isZero();
        assertThat(measured.restores().p95Seconds()).isNull();
        assertThat(measured.reconnects().missedRestores()).isOne();
        assertThat(measured.targets().noRestoreSlowerThan60s()).isFalse();
        assertThat(measured.targets().noReconnectsNeedingHands()).isFalse();

        // An answer 90 seconds after the callback completes it, too slowly.
        final Map<String, Object> restore = restoreRow();
        final Instant callbackAt = ((Timestamp) restore.get("callback_at")).toInstant();
        OwnerSql.client().sql("update reporting_restore set callback_at = now() - interval '2 minutes', renewed_at = now() - interval '2 minutes' + interval '1 second' where tenant_id = :t")
                .param("t", tenant.id()).update();
        final Instant movedCallback = ((Timestamp) restoreRow().get("callback_at")).toInstant();
        assertThat(movedCallback).isAfter(callbackAt);
        TenantContext.runAsTenant(tenant.id(), () -> gate1.recordReauthorization(reauthorized(movedCallback.plusSeconds(90), false)));
        measured = measure();
        assertThat(measured.restores().completed()).isOne();
        assertThat(measured.restores().slowerThan60s()).isOne();
        assertThat(measured.restores().maxSeconds()).isEqualTo(90L);
        assertThat(measured.targets().noRestoreSlowerThan60s()).isFalse();
    }

    @Test
    void ADR0018_aManualPaymentIsNotGate1() {
        final Api.Response paid = api.postWithKey("/api/v1/payments/manual", "gate1-cash-" + tenant.code(),
                "{\"customerId\":\"" + customerId() + "\",\"amount\":{\"amount\":250000,\"currency\":\"KES\"},"
                        + "\"channel\":\"CASH\",\"reference\":\"CASH-" + tenant.code() + "\"}");
        assertThat(paid.status()).as(paid.body()).isEqualTo(201);
        awaitCreditsApplied(2);
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(300)).until(() -> {
            TenantContext.callAsTenant(tenant.id(), relay::relayTenant);
            return OwnerSql.client().sql("select count(*) from shared_processed_event where consumer = 'reporting.credits' and tenant_id = :t")
                    .param("t", tenant.id()).query(Long.class).single() == 2;
        });
        assertThat(OwnerSql.client().sql("select count(*) from reporting_restore where tenant_id = :t")
                .param("t", tenant.id()).query(Long.class).single()).isZero();
        assertThat(measure().matching().automatic()).as("only the activation came by callback").isOne();
    }

    @Test
    void ADR0018_onlyPlatformTokensReadGate1() {
        assertThat(api.get("/api/platform/tenants/" + tenant.id() + "/gate1").status()).isEqualTo(403);
        final Api platform = platformAdmin();
        assertThat(platform.get("/api/platform/tenants/" + UUID.randomUUID() + "/gate1").status()).isEqualTo(404);
        assertThat(platform.get("/api/platform/tenants/" + tenant.id() + "/gate1?days=0").status()).isEqualTo(400);
        assertThat(platform.get("/api/platform/tenants/" + tenant.id() + "/gate1?days=91").status()).isEqualTo(400);
        final JsonNode noRestores = platform.get("/api/platform/tenants/" + tenant.id() + "/gate1?days=7").json();
        assertThat(noRestores.get("restores").get("total").asLong()).isZero();
        assertThat(noRestores.get("targets").get("noRestoreSlowerThan60s").isNull()).isTrue();
        assertThat(noRestores.get("targets").get("noReconnectsNeedingHands").asBoolean()).isTrue();

        // A tenant where nothing happened yet has nothing to judge.
        final UUID quiet = TestTenants.create(jdbc, tx);
        final JsonNode targets = platform.get("/api/platform/tenants/" + quiet + "/gate1").json().get("targets");
        assertThat(targets.get("matchingAbove98").isNull()).isTrue();
        assertThat(targets.get("noRestoreSlowerThan60s").isNull()).isTrue();
        assertThat(targets.get("noReconnectsNeedingHands").isNull()).isTrue();
    }

    private Gate1Dto measure() {
        final Instant now = clock.instant();
        return TenantContext.callAsTenant(tenant.id(), () -> gate1.gate1(now.minus(Duration.ofDays(1)), now.plusSeconds(1)));
    }

    private int complete() {
        return TenantContext.callAsTenant(tenant.id(), gate1::completeOpenRestores);
    }

    private SessionReauthorized reauthorized(final Instant at, final boolean sessionGone) {
        return new SessionReauthorized(UuidV7.next(clock), tenant.id(), at, lineId, routerId, "g1a-any",
                NetworkAccessChanged.Action.DISCONNECT, sessionGone);
    }

    private void publish(final SessionReauthorized event) {
        TenantContext.runAsTenant(tenant.id(), () -> tx.executeWithoutResult(s -> outbox.publish(event)));
    }

    /** A C2B confirmation for the package price by the customer number; M-Pesa's TransTime is a day off on purpose. */
    private String pay() throws Exception {
        final String txn = "QG1" + tenant.code() + TXN.incrementAndGet();
        final String body = """
                {"TransactionType":"Pay Bill","TransID":"%s","TransTime":"20260101080000","TransAmount":"2500.00",
                 "BusinessShortCode":"600984","BillRefNumber":"%s","MSISDN":"2547 ***** 222","FirstName":"AKINYI"}
                """.formatted(txn, customerNumber);
        final HttpResponse<String> ack = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/callbacks/mpesa/" + token + "/c2b/confirmation"))
                        .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(ack.statusCode()).as(ack.body()).isEqualTo(200);
        return await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(300)).until(
                () -> OwnerSql.client().sql("select id::text from payments_payment where tenant_id = :t and provider_txn_id = :txn")
                        .param("t", tenant.id()).param("txn", txn).query(String.class).optional().orElse(null),
                id -> id != null);
    }

    private void awaitCreditsApplied(final long count) {
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(300)).until(() -> {
            TenantContext.callAsTenant(tenant.id(), relay::relayTenant);
            return OwnerSql.client().sql("select count(*) from outbox_event where tenant_id = :t and event_type = 'CreditApplied'")
                    .param("t", tenant.id()).query(Long.class).single() >= count;
        });
    }

    private Map<String, Object> awaitRestore() {
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(300)).until(() -> {
            TenantContext.callAsTenant(tenant.id(), relay::relayTenant);
            return OwnerSql.client().sql("select count(*) from reporting_restore where tenant_id = :t")
                    .param("t", tenant.id()).query(Long.class).single() == 1;
        });
        return restoreRow();
    }

    private Map<String, Object> restoreRow() {
        return OwnerSql.client().sql("select * from reporting_restore where tenant_id = :t")
                .param("t", tenant.id()).query().singleRow();
    }

    private Instant callbackReceivedAt(final String paymentId) {
        return OwnerSql.client().sql("select received_at from payments_mpesa_callback where tenant_id = :t and payment_id = :p")
                .param("t", tenant.id()).param("p", UUID.fromString(paymentId)).query(Timestamp.class).single().toInstant();
    }

    private String customerId() {
        return OwnerSql.client().sql("select customer_id::text from subscriptions_subscription where id = :id")
                .param("id", lineId).query(String.class).single();
    }

    private void openSession(final String acctSessionId, final Instant start) {
        OwnerSql.client().sql("""
                        insert into radacct (acctsessionid, acctuniqueid, username, nasipaddress, acctstarttime)
                        values (:sid, :uid, :u, cast(:nas as inet), :start)
                        """)
                .param("sid", acctSessionId).param("uid", "u-" + acctSessionId + "-" + tenant.id()).param("u", username)
                .param("nas", tunnelIp).param("start", Timestamp.from(start))
                .update();
    }

    /** A platform admin straight in the table (token version 0, ACTIVE), so its signed token passes the version check. */
    private Api platformAdmin() {
        final UUID id = UUID.randomUUID();
        OwnerSql.client().sql("""
                        insert into identity_platform_admin (id, email, display_name, password_hash, must_change_password)
                        values (:id, :email, 'Gate One', 'not-a-hash', false)
                        """)
                .param("id", id).param("email", "gate1-" + id + "@corebalt.example.test").update();
        return new Api(port, TestTokens.sign(Map.of("sub", id.toString(), Tokens.TYPE, Tokens.PLATFORM, Tokens.VERSION, 0)));
    }
}
