package com.corebalt.isp.network;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.corebalt.isp.network.application.RouterPoller;
import com.corebalt.isp.shared.outbox.OutboxRelay;
import com.corebalt.isp.shared.tenant.TenantContext;
import com.corebalt.isp.support.Api;
import com.corebalt.isp.support.FakeRouterOs;
import com.corebalt.isp.support.IntegrationTest;
import com.corebalt.isp.support.OwnerSql;
import com.corebalt.isp.support.TestTenants;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.List;
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
 * Router liveness (docs/06 Monitoring, N6, T4) against a fake RouterOS REST API: the poller is
 * called directly here (the tunnel worker schedules it; TunnelWorkerIT boots that mode).
 */
class RouterMonitoringIT extends IntegrationTest {

    private static final AtomicInteger HOSTS = new AtomicInteger();

    @Value("${local.server.port}")
    int port;

    @Autowired JdbcClient jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired RouterPoller poller;
    @Autowired OutboxRelay relay;

    UUID tenant;
    Api api;
    String routerId;
    String tunnelIp;

    @BeforeEach
    void registerRouter() {
        tenant = TestTenants.create(jdbc, tx);
        api = new Api(port, tenant);
        tunnelIp = "127.0.5." + (HOSTS.incrementAndGet() + 1);
        final Api.Response created = api.post("/api/v1/routers", "{\"name\":\"Ruiru-" + HOSTS.get() + "\",\"tunnelIp\":\"" + tunnelIp + "\"}");
        assertThat(created.status()).as(created.body()).isEqualTo(201);
        routerId = created.json().get("router").get("id").asString();
        ROUTER_OS.up(tunnelIp);
    }

    @Test
    void T4_theRestCredentialIsWriteOnlyAndEncrypted() {
        assertThat(router().get("status").asString()).isEqualTo("NOT_MONITORED");
        assertThat(poll()).as("nothing to poll without a credential").isZero();

        final Api.Response set = setCredential(FakeRouterOs.PASSWORD);
        assertThat(set.status()).isEqualTo(200);
        assertThat(set.body()).doesNotContain(FakeRouterOs.PASSWORD);
        assertThat(set.json().get("status").asString()).isEqualTo("UNKNOWN");
        assertThat(set.json().get("restUsername").asString()).isEqualTo(FakeRouterOs.USERNAME);
        final byte[] stored = OwnerSql.client().sql("select rest_password_enc from network_router where id = :id")
                .param("id", UUID.fromString(routerId)).query(byte[].class).single();
        assertThat(new String(stored, StandardCharsets.ISO_8859_1)).doesNotContain(FakeRouterOs.PASSWORD);
        assertThat(OwnerSql.client().sql("select count(*) from identity_audit_log where target_id = :id and action = 'network.router_rest_credential_set'")
                .param("id", UUID.fromString(routerId)).query(Long.class).single()).isOne();
    }

    @Test
    void anAnswerMakesTheRouterOnlineWithItsResources() {
        setCredential(FakeRouterOs.PASSWORD);
        assertThat(poll()).isOne();
        final JsonNode router = router();
        assertThat(router.get("status").asString()).isEqualTo("ONLINE");
        assertThat(router.get("routerosVersion").asString()).isEqualTo("7.16.1 (stable)");
        assertThat(router.get("boardName").asString()).isEqualTo("CHR");
        assertThat(router.get("uptimeSeconds").asLong()).isEqualTo(7 * 86_400 + 2 * 86_400 + 3 * 3_600 + 4 * 60 + 5);
        assertThat(router.get("cpuLoad").asInt()).isEqualTo(3);
        assertThat(router.get("lastSeenAt").isNull()).isFalse();
    }

    @Test
    void ADR0011_thePollKeepsTheRoutersSessionListBesideRadacct() {
        assertThat(api.get("/api/v1/routers/" + routerId + "/sessions").json().get("router").isNull())
                .as("no snapshot before a poll").isTrue();
        setCredential(FakeRouterOs.PASSWORD);
        ROUTER_OS.sessions(tunnelIp, List.of("ZZ000002", "ZZ000001"));
        OwnerSql.client().sql("""
                        insert into radacct (acctsessionid, acctuniqueid, username, nasipaddress, acctstarttime)
                        values ('s1', :u1, 'ZZ000001', cast(:nas as inet), now() - interval '10 minutes'),
                               ('s3', :u3, 'ZZ000003', cast(:nas as inet), now() - interval '2 minutes')
                        """)
                .param("u1", "mon-1-" + tenant).param("u3", "mon-3-" + tenant).param("nas", tunnelIp).update();

        assertThat(poll()).isOne();

        final JsonNode sessions = api.get("/api/v1/routers/" + routerId + "/sessions").json();
        assertThat(sessions.get("router").get("count").asInt()).isEqualTo(2);
        assertThat(sessions.get("router").get("usernames")).extracting(JsonNode::asString).containsExactly("ZZ000001", "ZZ000002");
        assertThat(sessions.get("router").get("ageSeconds").asLong()).isBetween(0L, 30L);
        assertThat(sessions.get("radius")).extracting(s -> s.get("username").asString()).containsExactly("ZZ000001", "ZZ000003");
        assertThat(sessions.get("onlyInRadius")).extracting(JsonNode::asString).containsExactly("ZZ000003");
        assertThat(sessions.get("onlyOnRouter")).extracting(JsonNode::asString).containsExactly("ZZ000002");
        assertThat(api.get("/api/v1/routers/" + UUID.randomUUID() + "/sessions").status()).isEqualTo(404);
    }

    @Test
    void threeMissedPollsMakeItOfflineAlertStaffAndAnAnswerClearsIt() {
        setCredential(FakeRouterOs.PASSWORD);
        poll();
        ROUTER_OS.down(tunnelIp);
        poll();
        poll();
        assertThat(router().get("status").asString()).as("two misses are not an outage").isEqualTo("ONLINE");
        assertThat(events("RouterOffline")).isZero();

        poll();
        final JsonNode offline = router();
        assertThat(offline.get("status").asString()).isEqualTo("OFFLINE");
        assertThat(offline.get("lastPollError").asString()).isEqualTo("HTTP 503");
        assertThat(offline.get("offlineSince").isNull()).isFalse();
        assertThat(events("RouterOffline")).isOne();
        poll();
        assertThat(events("RouterOffline")).as("published once per outage").isOne();

        relayAndAwaitAlert(true);

        ROUTER_OS.up(tunnelIp);
        poll();
        assertThat(router().get("status").asString()).isEqualTo("ONLINE");
        assertThat(router().get("offlineSince").isNull()).isTrue();
        assertThat(events("RouterOnline")).isOne();
        relayAndAwaitAlert(false);
    }

    @Test
    void aRefusedLoginIsAMissWithItsReason() {
        setCredential("not-the-routers-password");
        assertThat(poll()).isZero();
        assertThat(router().get("lastPollError").asString()).isEqualTo("REST login refused (401)");
        assertThat(router().get("status").asString()).isEqualTo("UNKNOWN");
    }

    @Test
    void staffCanAskForAPollNowAndStopMonitoring() {
        setCredential(FakeRouterOs.PASSWORD);
        assertThat(api.post("/api/v1/routers/" + routerId + "/poll").status()).isEqualTo(202);
        assertThat(events("RouterPollRequested")).isOne();
        assertThat(new Api(port, tenant, "customers.write").post("/api/v1/routers/" + routerId + "/poll").status()).isEqualTo(403);

        assertThat(api.delete("/api/v1/routers/" + routerId + "/rest-credential").json().get("status").asString())
                .isEqualTo("NOT_MONITORED");
        assertThat(OwnerSql.client().sql("select rest_password_enc is null from network_router where id = :id")
                .param("id", UUID.fromString(routerId)).query(Boolean.class).single()).isTrue();
    }

    // --- helpers ---

    private Api.Response setCredential(final String password) {
        return api.put("/api/v1/routers/" + routerId + "/rest-credential",
                "{\"username\":\"" + FakeRouterOs.USERNAME + "\",\"password\":\"" + password + "\"}");
    }

    private int poll() {
        return TenantContext.callAsTenant(tenant, poller::pollCurrentTenant);
    }

    private JsonNode router() {
        return api.get("/api/v1/routers/" + routerId).json();
    }

    private long events(final String type) {
        return OwnerSql.client().sql("select count(*) from outbox_event where tenant_id = :t and event_type = :type")
                .param("t", tenant).param("type", type).query(Long.class).single();
    }

    private void relayAndAwaitAlert(final boolean open) {
        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(300)).untilAsserted(() -> {
            TenantContext.callAsTenant(tenant, relay::relayTenant);
            final Map<String, Object> alert = OwnerSql.client().sql("""
                            select count(*) filter (where cleared_at is null) as open, count(*) as total
                            from notifications_alert where tenant_id = :t and kind = 'ROUTER_OFFLINE' and subject_id = :r
                            """)
                    .param("t", tenant).param("r", UUID.fromString(routerId)).query().singleRow();
            assertThat(alert).containsEntry("total", 1L).containsEntry("open", open ? 1L : 0L);
        });
    }
}
