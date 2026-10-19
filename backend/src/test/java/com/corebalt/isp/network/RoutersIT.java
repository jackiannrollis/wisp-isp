package com.corebalt.isp.network;

import static org.assertj.core.api.Assertions.assertThat;

import com.corebalt.isp.support.Api;
import com.corebalt.isp.support.Catalog;
import com.corebalt.isp.support.IntegrationTest;
import com.corebalt.isp.support.OwnerSql;
import com.corebalt.isp.support.TestTenants;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/** Router registration and its RADIUS secret (docs/06, ADR 0009, N2, T4). */
class RoutersIT extends IntegrationTest {

    @Value("${local.server.port}")
    int port;

    @Autowired JdbcClient jdbc;
    @Autowired TransactionTemplate tx;

    Api api;

    @BeforeEach
    void tenant() {
        api = new Api(port, TestTenants.create(jdbc, tx));
    }

    @Test
    void T4_secretIsShownOnceAtRegistrationAndNeverAgain() {
        final Api.Response created = Catalog.router(api, "Utawala-01");
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.header("Cache-Control")).contains("no-store");
        final JsonNode body = created.json();
        final String secret = body.get("radiusSecret").asString();
        assertThat(secret).hasSize(32).matches("[A-Za-z2-9]+");

        final String id = body.get("router").get("id").asString();
        final Api.Response read = api.get("/api/v1/routers/" + id);
        assertThat(read.status()).isEqualTo(200);
        assertThat(read.body()).doesNotContain(secret).doesNotContain("radiusSecret");
        assertThat(api.get("/api/v1/routers").body()).doesNotContain(secret);
    }

    @Test
    void N2_freeRadiusSeesTheRouterWithItsCurrentSecret() {
        final JsonNode created = Catalog.router(api, "Kitengela-01").json();
        final String tunnelIp = created.get("router").get("tunnelIp").asString();
        assertThat(radiusClient(tunnelIp)).containsEntry("secret", created.get("radiusSecret").asString());

        final String regenerated =
                api.post("/api/v1/routers/" + created.get("router").get("id").asString() + "/radius-secret")
                        .json()
                        .get("radiusSecret")
                        .asString();
        assertThat(regenerated).isNotEqualTo(created.get("radiusSecret").asString());
        assertThat(radiusClient(tunnelIp)).containsEntry("secret", regenerated);
    }

    @Test
    void A1_secretChangesAreAuditedWithoutTheSecret() {
        final JsonNode created = Catalog.router(api, "Rongai-01").json();
        final String id = created.get("router").get("id").asString();
        api.post("/api/v1/routers/" + id + "/radius-secret");
        final var rows =
                OwnerSql.client()
                        .sql("select action, coalesce(after_state::text, '') from identity_audit_log where target_id = cast(:id as uuid) order by occurred_at")
                        .param("id", id)
                        .query((rs, n) -> rs.getString(1) + " " + rs.getString(2))
                        .list();
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).startsWith("network.radius_secret_issued");
        assertThat(rows.get(1)).startsWith("network.radius_secret_regenerated");
        assertThat(String.join(" ", rows)).doesNotContain(created.get("radiusSecret").asString());
    }

    @Test
    void tunnelAddressesAreUniqueAcrossTenants() {
        final String tunnelIp = Catalog.router(api, "First").json().get("router").get("tunnelIp").asString();
        final Api other = new Api(port, TestTenants.create(jdbc, tx));
        final Api.Response clash = other.post("/api/v1/routers", "{\"name\":\"Clash\",\"tunnelIp\":\"" + tunnelIp + "\"}");
        assertThat(clash.status()).isEqualTo(409);
        assertThat(clash.body()).doesNotContain("network_router").doesNotContain("duplicate key");
    }

    @Test
    void tunnelIpMustBeAnIpv4Address() {
        assertThat(api.post("/api/v1/routers", "{\"name\":\"Bad\",\"tunnelIp\":\"10.200.0.300\"}").status())
                .isEqualTo(400);
        assertThat(api.post("/api/v1/routers", "{\"name\":\"Bad\",\"tunnelIp\":\"router.example\"}").status())
                .isEqualTo(400);
    }

    private static Map<String, Object> radiusClient(final String nasname) {
        return OwnerSql.client()
                .sql("select nasname, secret from radius_client_v3 where nasname = :nasname")
                .param("nasname", nasname)
                .query()
                .singleRow();
    }
}
