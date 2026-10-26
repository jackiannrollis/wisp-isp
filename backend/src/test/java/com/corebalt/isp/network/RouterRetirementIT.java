package com.corebalt.isp.network;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.corebalt.isp.shared.outbox.OutboxRelay;
import com.corebalt.isp.shared.tenant.TenantContext;
import com.corebalt.isp.support.Api;
import com.corebalt.isp.support.Catalog;
import com.corebalt.isp.support.IntegrationTest;
import com.corebalt.isp.support.OwnerSql;
import com.corebalt.isp.support.TestTenants;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/** Retiring a router (N10, ADR 0015): final, its lines moved, its secret, login and sessions gone. */
class RouterRetirementIT extends IntegrationTest {

    private static final String REASON = "{\"reason\":\"Replaced after a lightning strike\"";

    @Value("${local.server.port}")
    int port;

    @Autowired JdbcClient jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired OutboxRelay relay;

    UUID tenant;
    Api api;
    String packageId;
    String oldRouter;
    String oldTunnelIp;
    String newRouter;

    @BeforeEach
    void routers() {
        tenant = TestTenants.create(jdbc, tx);
        api = new Api(port, tenant);
        packageId = Catalog.pppoePackage(api, "Home 10");
        final JsonNode old = Catalog.router(api, "Utawala-01").json().get("router");
        oldRouter = old.get("id").asString();
        oldTunnelIp = old.get("tunnelIp").asString();
        newRouter = Catalog.router(api, "Utawala-02").json().get("router").get("id").asString();
    }

    @Test
    void N10_retiringMovesLiveLinesRevokesTheSecretClosesSessionsAndClearsTheAlert() {
        final String live = line("Wanjiku Kamau", "0712000001", oldRouter);
        final String cancelled = line("Changed Mind", "0712000002", oldRouter);
        assertThat(api.postWithKey("/api/v1/subscriptions/" + cancelled + "/cancel", "cancel-1", "{\"reason\":\"never installed\"}")
                .status()).isEqualTo(200);
        assertThat(api.put("/api/v1/routers/" + oldRouter + "/rest-credential",
                "{\"username\":\"corebalt-api\",\"password\":\"rest-password-fake-1\"}").status()).isEqualTo(200);
        OwnerSql.client().sql("""
                        insert into radacct (acctsessionid, acctuniqueid, username, nasipaddress, acctstarttime, acctupdatetime)
                        values ('s-retire', :uid, 'x', cast(:nas as inet), :start, :updated)
                        """)
                .param("uid", "u-retire-" + tenant).param("nas", oldTunnelIp)
                .param("start", Timestamp.from(Instant.now().minus(Duration.ofHours(2))))
                .param("updated", Timestamp.from(Instant.now().minus(Duration.ofMinutes(3))))
                .update();
        OwnerSql.client().sql("""
                        insert into notifications_alert (id, tenant_id, kind, subject_id, detail, raised_at)
                        values (gen_random_uuid(), :t, 'ROUTER_OFFLINE', :r, 'router did not answer 3 polls', now())
                        """)
                .param("t", tenant).param("r", UUID.fromString(oldRouter)).update();
        final String path = "/api/v1/routers/" + oldRouter + "/retire";

        assertThat(api.post(path, REASON + "}").status()).as("an Idempotency-Key is required").isEqualTo(400);
        final Api.Response stillHasLines = api.postWithKey(path, "retire-0", REASON + "}");
        assertThat(stillHasLines.status()).isEqualTo(409);
        assertThat(stillHasLines.body()).contains("N10").contains("1 line");

        final String body = REASON + ",\"replacementRouterId\":\"" + newRouter + "\"}";
        final Api.Response retired = api.postWithKey(path, "retire-1", body);
        assertThat(retired.status()).as(retired.body()).isEqualTo(200);
        final JsonNode router = retired.json();
        assertThat(router.get("retiredAt").isNull()).isFalse();
        assertThat(router.get("retiredReason").asString()).isEqualTo("Replaced after a lightning strike");
        assertThat(router.get("replacedByRouterId").asString()).isEqualTo(newRouter);
        assertThat(router.get("status").asString()).isEqualTo("NOT_MONITORED");
        assertThat(router.get("restUsername").isNull()).isTrue();

        // Lines: the live one follows the replacement; the terminated one stays as history.
        assertThat(api.get("/api/v1/subscriptions/" + live).json().get("routerId").asString()).isEqualTo(newRouter);
        assertThat(api.get("/api/v1/subscriptions/" + cancelled).json().get("routerId").asString()).isEqualTo(oldRouter);
        // FreeRADIUS no longer lists it as a client (N2: radius_client_v3 shows routers with a secret only).
        assertThat(OwnerSql.client().sql("select count(*) from radius_client_v3 where nasname = :n")
                .param("n", oldTunnelIp).query(Long.class).single()).isZero();
        // Its open session ended at its last update, so no CoA is ever aimed at it.
        assertThat(OwnerSql.client().sql("select acctterminatecause from radacct where acctsessionid = 's-retire' and tenant_id = :t")
                .param("t", tenant).query(String.class).single()).isEqualTo("Router-Retired");
        assertThat(api.get("/api/v1/dashboard").json().get("network").get("activeSessions").asLong()).isZero();
        // The offline alert is cleared by the RouterRetired event.
        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(300)).untilAsserted(() -> {
            TenantContext.callAsTenant(tenant, relay::relayTenant);
            assertThat(OwnerSql.client().sql("""
                            select count(*) from notifications_alert
                            where tenant_id = :t and kind = 'ROUTER_OFFLINE' and subject_id = :r and cleared_at is null
                            """)
                    .param("t", tenant).param("r", UUID.fromString(oldRouter)).query(Long.class).single()).isZero();
        });
        // Audited with the reason and what moved (A1); one event.
        final String audit = OwnerSql.client().sql("""
                        select reason || ' ' || after_state::text from identity_audit_log
                        where tenant_id = :t and action = 'network.router_retired' and target_id = :r
                        """)
                .param("t", tenant).param("r", UUID.fromString(oldRouter)).query(String.class).single();
        assertThat(audit).startsWith("Replaced after a lightning strike").contains("\"linesMoved\": 1").contains("\"sessionsClosed\": 1");
        assertThat(OwnerSql.client().sql("select count(*) from outbox_event where tenant_id = :t and event_type = 'RouterRetired'")
                .param("t", tenant).query(Long.class).single()).isOne();

        final Api.Response replay = api.postWithKey(path, "retire-1", body);
        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.header("Idempotent-Replayed")).isEqualTo("true");
        assertThat(replay.json().get("retiredAt").asString()).isEqualTo(router.get("retiredAt").asString());

        // Lists: active routers by default, history on request; the dashboard counts active routers only.
        assertThat(api.get("/api/v1/routers").body()).doesNotContain(oldRouter).contains(newRouter);
        assertThat(api.get("/api/v1/routers?includeRetired=true").body()).contains(oldRouter);
        final JsonNode byStatus = api.get("/api/v1/dashboard").json().get("network").get("routersByStatus");
        long routers = 0;
        for (final JsonNode count : byStatus) {
            routers += count.asLong();
        }
        assertThat(routers).isOne();
    }

    @Test
    void N10_retirementIsFinalAndTheNameMayBeReusedButNeverTheAddress() {
        final String path = "/api/v1/routers/" + oldRouter;
        assertThat(api.postWithKey(path + "/retire", "self", REASON + ",\"replacementRouterId\":\"" + oldRouter + "\"}").status())
                .as("a router cannot replace itself").isEqualTo(409);
        assertThat(api.postWithKey(path + "/retire", "r1", REASON + "}").status()).isEqualTo(200);

        assertThat(api.postWithKey(path + "/retire", "r2", REASON + "}").status()).as("final").isEqualTo(409);
        assertThat(api.post(path + "/radius-secret").status()).isEqualTo(409);
        assertThat(api.post(path + "/poll").status()).isEqualTo(409);
        assertThat(api.put(path + "/rest-credential", "{\"username\":\"u\",\"password\":\"rest-password-fake-1\"}").status())
                .isEqualTo(409);
        assertThat(api.post(path + "/onboarding-script", """
                {"pppoeInterface":"ether2","pppLocalAddress":"10.10.0.1","poolFull":"10.10.0.10-10.10.0.250",
                 "poolRestricted":"10.11.0.10-10.11.0.250","managementCidrs":"192.168.88.0/24"}
                """).status()).isEqualTo(409);
        final String customer = Catalog.customer(api, "Late Comer", "0712000009");
        assertThat(api.post("/api/v1/subscriptions", "{\"customerId\":\"" + customer + "\",\"packageId\":\"" + packageId
                + "\",\"routerId\":\"" + oldRouter + "\"}").status()).as("no new lines on a retired router").isEqualTo(404);
        assertThat(api.get(path).json().get("retiredAt").isNull()).as("still readable as history").isFalse();

        // Its name goes to the replacement; its tunnel address does not (the RADIUS view lists routers by address).
        assertThat(api.post("/api/v1/routers", "{\"name\":\"utawala-01\",\"tunnelIp\":\"10.201.250.1\"}").status()).isEqualTo(201);
        assertThat(api.post("/api/v1/routers", "{\"name\":\"Elsewhere\",\"tunnelIp\":\"" + oldTunnelIp + "\"}").status())
                .isEqualTo(409);
        // A retired router cannot take another's lines.
        final Api.Response toRetired = api.postWithKey("/api/v1/routers/" + newRouter + "/retire", "r3",
                REASON + ",\"replacementRouterId\":\"" + oldRouter + "\"}");
        assertThat(toRetired.status()).isEqualTo(400);
        assertThat(toRetired.body()).contains("replacementRouterId");
        // Staff without network.write cannot retire.
        assertThat(new Api(port, tenant, "subscriptions.write").postWithKey("/api/v1/routers/" + newRouter + "/retire", "r4",
                REASON + "}").status()).isEqualTo(403);
    }

    private String line(final String name, final String phone, final String routerId) {
        final String customer = Catalog.customer(api, name, phone);
        final Api.Response opened = api.post("/api/v1/subscriptions",
                "{\"customerId\":\"" + customer + "\",\"packageId\":\"" + packageId + "\",\"routerId\":\"" + routerId + "\"}");
        assertThat(opened.status()).as(opened.body()).isEqualTo(201);
        return opened.json().get("subscription").get("id").asString();
    }
}
