package com.corebalt.isp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.corebalt.isp.billing.application.WalletService;
import com.corebalt.isp.network.infrastructure.RadiusPacket;
import com.corebalt.isp.shared.money.Money;
import com.corebalt.isp.shared.outbox.OutboxRelay;
import com.corebalt.isp.shared.tenant.TenantContext;
import com.corebalt.isp.subscriptions.api.RenewalResultDto;
import com.corebalt.isp.subscriptions.application.RenewalService;
import com.corebalt.isp.support.Api;
import com.corebalt.isp.support.Catalog;
import com.corebalt.isp.support.FakeNas;
import com.corebalt.isp.support.IntegrationTest;
import com.corebalt.isp.support.OwnerSql;
import com.corebalt.isp.support.TestTenants;
import com.corebalt.isp.support.TestTokens;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * CoA and Disconnect end to end (N5, ADR 0011): a staff action in the API, its relay, the API's
 * NetworkAccessChanged for the live session, and the tunnel worker, connected as isp_worker, sending
 * to a fake router and publishing the outcome, which notifications turns into an alert or clears.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CoaWorkerIT extends IntegrationTest {

    private static final AtomicInteger HOST = new AtomicInteger(10);

    @Value("${local.server.port}")
    int port;

    @Autowired JdbcClient jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired OutboxRelay relay;
    @Autowired RenewalService renewals;
    @Autowired WalletService wallets;
    @Autowired Environment environment;

    /** Holds the port every fake router listens on, on its own loopback address. */
    FakeNas portHolder;
    ConfigurableApplicationContext worker;
    FakeNas nas;

    UUID tenant;
    Api api;
    String routerId;
    String secret;
    UUID lineId;
    String username;
    String packageId;

    @BeforeAll
    void startWorker() {
        portHolder = new FakeNas("127.0.8.1", 0, "unused-fake-secret");
        worker = new SpringApplication(IspApplication.class).run(
                "--spring.profiles.active=test,worker",
                "--server.port=0",
                "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=isp_worker",
                "--spring.datasource.password=isp_worker_test_only",
                "--spring.rabbitmq.host=" + environment.getProperty("spring.rabbitmq.host"),
                "--spring.rabbitmq.port=" + environment.getProperty("spring.rabbitmq.port"),
                "--spring.rabbitmq.username=" + environment.getProperty("spring.rabbitmq.username"),
                "--spring.rabbitmq.password=" + environment.getProperty("spring.rabbitmq.password"),
                "--spring.data.redis.host=" + environment.getProperty("spring.data.redis.host"),
                "--spring.data.redis.port=" + environment.getProperty("spring.data.redis.port"),
                "--isp.secrets.master-key=" + environment.getProperty("isp.secrets.master-key"),
                "--isp.worker.poll-interval=1h",
                "--isp.worker.coa.port=" + portHolder.port(),
                "--isp.worker.coa.timeout=300ms",
                "--isp.worker.coa.backoff=100ms");
    }

    @AfterAll
    void stopWorker() {
        if (worker != null) {
            worker.close();
        }
        portHolder.close();
    }

    @AfterEach
    void closeNas() {
        if (nas != null) {
            nas.close();
        }
    }

    @Test
    void N5_aPauseDisconnectsTheLiveSessionThroughTheWorker() {
        activeLineWithSession("81b00001");

        pause();

        final FakeNas.Received request = await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(300))
                .until(() -> {
                    relayAll();
                    return nas.received().stream().findFirst().orElse(null);
                }, r -> r != null);
        assertThat(request.signedWithSecret()).isTrue();
        assertThat(request.packet().code()).isEqualTo(RadiusPacket.DISCONNECT_REQUEST);
        assertThat(request.packet().string(RadiusPacket.USER_NAME)).contains(username);
        assertThat(request.packet().string(RadiusPacket.ACCT_SESSION_ID)).contains("81b00001");
        awaitOutbox("SessionReauthorized", 1);
        // The API keeps the outcome per line for the pay page.
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(300)).untilAsserted(() -> {
            relayAll();
            assertThat(OwnerSql.client().sql("select last_coa_action || ':' || last_coa_result from network_line_coa where subscription_id = :s")
                    .param("s", lineId).query(String.class).optional()).contains("DISCONNECT:ACK");
        });
    }

    @Test
    void N5_thePayPageSeesTheLineRestoredOnceItsSessionWasChanged() {
        activeLineWithSession("81b00006");
        final Instant paid = Instant.now();
        final String customer = OwnerSql.client().sql("select customer_id::text from subscriptions_subscription where id = :id")
                .param("id", lineId).query(String.class).single();
        OwnerSql.client().sql("insert into identity_customer_credential (tenant_id, customer_id) values (:t, cast(:c as uuid))")
                .param("t", tenant).param("c", customer).update();
        final Api portal = new Api(port, TestTokens.customer(UUID.fromString(customer), tenant));
        final String path = "/api/v1/portal/lines/" + lineId + "/restore-status?since=" + paid;

        // FULL, but the session from before the payment has not been changed yet.
        final JsonNode before = portal.get(path).json();
        assertThat(before.get("accessProfile").asString()).isEqualTo("FULL");
        assertThat(before.get("sessionsFromBefore").asLong()).isOne();
        assertThat(before.get("restored").asBoolean()).isFalse();

        pause();
        resume();
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(300)).untilAsserted(() -> {
            relayAll();
            final JsonNode after = portal.get(path).json();
            assertThat(after.get("lastCoaResult").asString()).isEqualTo("ACK");
            assertThat(after.get("restored").asBoolean()).isTrue();
        });
        assertThat(new Api(port, TestTokens.customer(UUID.randomUUID(), tenant)).get(path).status()).isIn(401, 404);
    }

    private void resume() {
        assertThat(api.postWithKey("/api/v1/subscriptions/" + lineId + "/resume", UUID.randomUUID().toString(), null).status())
                .isEqualTo(200);
    }

    @Test
    void N5_S10_anUpgradeNowSetsTheNewRateByCoa() {
        activeLineWithSession("81b00002");
        fund(400_000);
        final String faster = api.post("/api/v1/packages", """
                {"name":"Home 25","downloadKbps":25000,"uploadKbps":8000,"durationMinutes":43200,
                 "price":{"amount":400000,"currency":"KES"}}""").json().get("id").asString();
        assertThat(api.postWithKey("/api/v1/subscriptions/" + lineId + "/package", UUID.randomUUID().toString(),
                "{\"packageId\":\"" + faster + "\",\"timing\":\"NOW\"}").status()).isEqualTo(200);

        awaitOutbox("SessionReauthorized", 1);
        assertThat(nas.received()).singleElement().satisfies(r -> {
            assertThat(r.packet().code()).isEqualTo(RadiusPacket.COA_REQUEST);
            assertThat(r.packet().vendorString(RadiusPacket.MIKROTIK_VENDOR, RadiusPacket.MIKROTIK_RATE_LIMIT))
                    .contains("8M/25M");
        });
    }

    @Test
    void N5_aSilentRouterGetsThreeRetriesThenStaffAreAlerted_aLaterAckClearsTheAlert() {
        activeLineWithSession("81b00003");
        nas.mode(FakeNas.Mode.SILENT);

        pause();
        awaitOutbox("SessionReauthorizationFailed", 1);
        assertThat(nas.received()).hasSize(1 + 3).allSatisfy(r -> assertThat(r.signedWithSecret()).isTrue());
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(300)).untilAsserted(() -> {
            relayAll();
            assertThat(openAlerts()).containsExactly(lineId);
        });
        final JsonNode alert = api.get("/api/v1/notifications/alerts").json().get("items").get(0);
        assertThat(alert.get("kind").asString()).isEqualTo("COA_FAILED");
        assertThat(alert.get("subjectLabel").asString()).as("staff see the PPPoE username").isEqualTo(username);
        assertThat(alert.get("customerId").asString()).isEqualTo(OwnerSql.client()
                .sql("select customer_id::text from subscriptions_subscription where id = :id").param("id", lineId)
                .query(String.class).single());

        nas.mode(FakeNas.Mode.ACK);
        assertThat(api.postWithKey("/api/v1/subscriptions/" + lineId + "/resume", UUID.randomUUID().toString(), null).status())
                .isEqualTo(200);
        awaitOutbox("SessionReauthorized", 1);
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(300)).untilAsserted(() -> {
            relayAll();
            assertThat(openAlerts()).isEmpty();
        });
    }

    @Test
    void ADR0009_aRotatedRouterSecretIsUsedOnTheNextRequest() {
        activeLineWithSession("81b00004");
        pause();
        awaitOutbox("SessionReauthorized", 1);

        final String rotated = api.post("/api/v1/routers/" + routerId + "/radius-secret").json().get("radiusSecret").asString();
        assertThat(rotated).isNotEqualTo(secret);
        nas.secret(rotated);
        assertThat(api.postWithKey("/api/v1/subscriptions/" + lineId + "/resume", UUID.randomUUID().toString(), null).status())
                .isEqualTo(200);

        awaitOutbox("SessionReauthorized", 2);
        assertThat(nas.received()).hasSize(2).allSatisfy(r -> assertThat(r.signedWithSecret()).isTrue());
    }

    @Test
    void N5_aSessionTheRouterNoLongerHasCountsAsDone() {
        activeLineWithSession("81b00005");
        nas.mode(FakeNas.Mode.SESSION_GONE);
        pause();
        awaitOutbox("SessionReauthorized", 1);
        assertThat(nas.received()).hasSize(1);
    }

    private void activeLineWithSession(final String acctSessionId) {
        tenant = TestTenants.create(jdbc, tx);
        api = new Api(port, tenant);
        final String tunnelIp = "127.0.8." + HOST.incrementAndGet();
        final JsonNode created = api.post("/api/v1/routers", "{\"name\":\"Ruiru-" + HOST.get() + "\",\"tunnelIp\":\"" + tunnelIp
                + "\"}").json();
        routerId = created.get("router").get("id").asString();
        secret = created.get("radiusSecret").asString();
        nas = new FakeNas(tunnelIp, portHolder.port(), secret);

        packageId = Catalog.pppoePackage(api, "Home 10");
        final String customerId = Catalog.customer(api, "Njeri Wambui", "0733000222");
        final JsonNode opened = api.post("/api/v1/subscriptions", "{\"customerId\":\"" + customerId + "\",\"packageId\":\""
                + packageId + "\",\"routerId\":\"" + routerId + "\"}").json();
        lineId = UUID.fromString(opened.get("subscription").get("id").asString());
        username = opened.get("subscription").get("pppoeUsername").asString();
        fund(250_000);
        assertThat(TenantContext.callAsTenant(tenant, () -> renewals.renew(lineId)).outcome())
                .isEqualTo(RenewalResultDto.Outcome.RENEWED);
        // Let the activation's events go through first: the line had no session then.
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(300)).untilAsserted(() -> {
            relayAll();
            assertThat(processed("network.state-changes")).isPositive();
            assertThat(processed("network.renewals")).isPositive();
        });

        OwnerSql.client().sql("""
                        insert into radacct (acctsessionid, acctuniqueid, username, nasipaddress, acctstarttime)
                        values (:sid, :uid, :u, cast(:nas as inet), :start)
                        """)
                .param("sid", acctSessionId).param("uid", "u-" + acctSessionId).param("u", username).param("nas", tunnelIp)
                .param("start", Timestamp.from(Instant.now().minus(Duration.ofMinutes(20))))
                .update();
    }

    private void pause() {
        final Instant until = Instant.now().plus(Duration.ofDays(3));
        assertThat(api.postWithKey("/api/v1/subscriptions/" + lineId + "/pause", UUID.randomUUID().toString(),
                "{\"pauseUntil\":\"" + until + "\"}").status()).isEqualTo(200);
    }

    private void fund(final long minor) {
        final UUID customerId = OwnerSql.client().sql("select customer_id from subscriptions_subscription where id = :id")
                .param("id", lineId).query(UUID.class).single();
        TenantContext.runAsTenant(tenant, () -> tx.executeWithoutResult(
                s -> wallets.adjust(customerId, Money.ofMinor(minor, "KES"), "test funding")));
    }

    private void relayAll() {
        TenantContext.callAsTenant(tenant, relay::relayTenant);
    }

    private void awaitOutbox(final String eventType, final int count) {
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(300)).untilAsserted(() -> {
            relayAll();
            assertThat(OwnerSql.client().sql("select count(*) from outbox_event where tenant_id = :t and event_type = :e")
                    .param("t", tenant).param("e", eventType).query(Long.class).single()).isEqualTo(count);
        });
    }

    private long processed(final String consumer) {
        return OwnerSql.client().sql("select count(*) from shared_processed_event where consumer = :c and tenant_id = :t")
                .param("c", consumer).param("t", tenant).query(Long.class).single();
    }

    private List<UUID> openAlerts() {
        return OwnerSql.client().sql("""
                        select subject_id from notifications_alert
                        where tenant_id = :t and kind = 'COA_FAILED' and cleared_at is null
                        """)
                .param("t", tenant).query(UUID.class).list();
    }
}
