package com.corebalt.isp.network;

import static org.assertj.core.api.Assertions.assertThat;

import com.corebalt.isp.support.Api;
import com.corebalt.isp.support.Catalog;
import com.corebalt.isp.support.IntegrationTest;
import com.corebalt.isp.support.OwnerSql;
import com.corebalt.isp.support.TestTenants;
import com.corebalt.isp.network.domain.WireGuardKeys;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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

    private static final String ONBOARDING = """
            {"pppoeInterface":"ether2","pppLocalAddress":"10.10.0.1","poolFull":"10.10.0.10-10.10.0.250",
             "poolRestricted":"10.11.0.10-10.11.0.250","managementCidrs":"192.168.88.0/24"}""";

    @Test
    void ADR0013_onboardingScriptIsShownOnceWithNewKeysAndRotatesTheSecrets() {
        final JsonNode created = Catalog.router(api, "Utawala-02").json();
        final String id = created.get("router").get("id").asString();
        final String tunnelIp = created.get("router").get("tunnelIp").asString();

        final Api.Response generated = api.post("/api/v1/routers/" + id + "/onboarding-script", ONBOARDING);
        assertThat(generated.status()).as(generated.body()).isEqualTo(200);
        assertThat(generated.header("Cache-Control")).contains("no-store");
        assertThat(generated.json().get("fileName").asString()).isEqualTo("corebalt-utawala-02.rsc");
        final String script = generated.json().get("script").asString();
        assertThat(script).doesNotContain("{{")
                .contains("identity set name=\"Utawala-02\"")
                .contains("address=" + tunnelIp + "/24")
                .contains("endpoint-address=" + IntegrationTest.HUB_ENDPOINT)
                .contains("public-key=\"" + IntegrationTest.HUB_PUBLIC_KEY + "\"")
                .contains("allowed-address=10.200.0.1/32")
                .contains("interface=ether2")
                .contains("ranges=10.10.0.10-10.10.0.250")
                .contains("to-addresses=" + IntegrationTest.PORTAL_ADDRESS + " to-ports=80")
                .contains("www address=10.200.0.0/24,192.168.88.0/24");
        final String privateKey = group(script, "private-key=\"([A-Za-z0-9+/]{43}=)\"");
        final String secret = group(script, " secret=\"([A-Za-z2-9]{32})\"");
        final String restPassword = group(script, "password=\"([A-Za-z2-9]{24})\"");

        final JsonNode router = generated.json().get("router");
        assertThat(router.get("wgPublicKey").asString()).as("the public key of the private key in the script")
                .isEqualTo(WireGuardKeys.publicKeyOf(privateKey));
        assertThat(router.get("restUsername").asString()).isEqualTo("corebalt-api");
        assertThat(router.get("status").asString()).isEqualTo("UNKNOWN");
        assertThat(router.get("pppoeInterface").asString()).isEqualTo("ether2");
        assertThat(router.get("pppLocalAddress").asString()).isEqualTo("10.10.0.1");
        assertThat(router.get("onboardingScriptGeneratedAt").isNull()).isFalse();
        assertThat(secret).isNotEqualTo(created.get("radiusSecret").asString());
        assertThat(radiusClient(tunnelIp)).as("FreeRADIUS loads the rotated secret at its next reload").containsEntry("secret", secret);

        final String read = api.get("/api/v1/routers/" + id).body();
        assertThat(read).contains(router.get("wgPublicKey").asString())
                .doesNotContain(privateKey).doesNotContain(secret).doesNotContain(restPassword).doesNotContain("script");
        final String audit = OwnerSql.client()
                .sql("select string_agg(action || ' ' || coalesce(after_state::text, ''), ' ') from identity_audit_log where target_id = cast(:id as uuid)")
                .param("id", id).query(String.class).single();
        assertThat(audit).contains("network.onboarding_script_generated").contains("network.radius_secret_regenerated")
                .contains("network.router_rest_credential_set")
                .doesNotContain(privateKey).doesNotContain(secret).doesNotContain(restPassword);

        // A second script: a new key pair, the old public key gone, every secret rotated again.
        final JsonNode again = api.post("/api/v1/routers/" + id + "/onboarding-script", ONBOARDING).json();
        assertThat(again.get("router").get("wgPublicKey").asString()).isNotEqualTo(router.get("wgPublicKey").asString());
        assertThat(again.get("script").asString()).doesNotContain(privateKey).doesNotContain(secret).doesNotContain(restPassword);
        assertThat(api.get("/api/v1/routers/" + id).body()).doesNotContain(router.get("wgPublicKey").asString());
    }

    @Test
    void onboardingScriptRefusesEqualPoolsAndBadAddresses() {
        final String id = Catalog.router(api, "Ruiru-01").json().get("router").get("id").asString();
        final String path = "/api/v1/routers/" + id + "/onboarding-script";
        assertThat(api.post(path, ONBOARDING.replace("10.11.0.10-10.11.0.250", "10.10.0.10-10.10.0.250")).status()).isEqualTo(400);
        assertThat(api.post(path, ONBOARDING.replace("\"ether2\"", "\"ether 2; /system reset\"")).status()).isEqualTo(400);
        assertThat(api.post(path, ONBOARDING.replace("10.10.0.1", "10.10.0.300")).status()).isEqualTo(400);
        assertThat(api.post(path, ONBOARDING.replace("192.168.88.0/24", "anywhere")).status()).isEqualTo(400);
        assertThat(api.post("/api/v1/routers/" + UUID.randomUUID() + "/onboarding-script", ONBOARDING).status()).isEqualTo(404);
    }

    private static String group(final String text, final String regex) {
        final Matcher m = Pattern.compile(regex).matcher(text);
        assertThat(m.find()).as(regex).isTrue();
        return m.group(1);
    }

    private static Map<String, Object> radiusClient(final String nasname) {
        return OwnerSql.client()
                .sql("select nasname, secret from radius_client_v3 where nasname = :nasname")
                .param("nasname", nasname)
                .query()
                .singleRow();
    }
}
