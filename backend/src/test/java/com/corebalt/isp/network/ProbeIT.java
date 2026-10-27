package com.corebalt.isp.network;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.corebalt.isp.network.application.ProbeSetup;
import com.corebalt.isp.shared.web.InvalidRequestException;
import com.corebalt.isp.support.Api;
import com.corebalt.isp.support.Catalog;
import com.corebalt.isp.support.IntegrationTest;
import com.corebalt.isp.support.OwnerSql;
import com.corebalt.isp.support.TestTenants;
import com.corebalt.isp.tenancy.api.ProbeTenant;
import com.corebalt.isp.tenancy.api.TenantRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The synthetic login probe's setup (ADR 0019) and S12 in the database: the probe tenant is INTERNAL and
 * invisible, its two lines are activated with cause PROBE and read by isp_worker through the probe-only
 * view (T4), which gives nothing of any other tenant; the trigger and checks refuse an activation without
 * a cause and PROBE on a real tenant.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ProbeIT extends IntegrationTest {

    @Value("${local.server.port}")
    int port;

    @Autowired ProbeSetup setup;
    @Autowired TenantRegistry tenants;
    @Autowired JdbcClient jdbc;
    @Autowired TransactionTemplate tx;

    @Test
    @Order(1)
    void ADR0019_S12_theSetupMakesTheProbeTenantTwoProbeLinesAndANasPerHub_andRunsAgainHarmlessly() {
        final ProbeSetup.Result first = setup.ensure("A", "127.0.0.3");
        assertThat(first.nasCreated()).isTrue();
        assertThat(first.nas()).isEqualTo("probe-a");
        assertThat(first.linesCreated()).isEqualTo(2);
        assertThat(first.lines()).extracting(l -> l.state() + ":" + l.expiresAt())
                .containsExactlyInAnyOrder("ACTIVE:" + ProbeSetup.FULL_UNTIL, "ACTIVE:" + ProbeSetup.RESTRICTED_SINCE);

        final List<Map<String, Object>> lines = OwnerSql.client()
                .sql("select tenant_id, activation_cause, pppoe_username from subscriptions_subscription where tenant_id = :t")
                .param("t", ProbeTenant.ID).query().listOfRows();
        assertThat(lines).hasSize(2).allSatisfy(l -> assertThat(l.get("activation_cause")).isEqualTo("PROBE"));
        assertThat(lines).allSatisfy(l -> assertThat((String) l.get("pppoe_username")).startsWith(ProbeTenant.CODE));

        final ProbeSetup.Result again = setup.ensure("a", "127.0.0.3");
        assertThat(again.nasCreated()).isFalse();
        assertThat(again.linesCreated()).isZero();
        assertThat(catchThrowable(() -> setup.ensure("A", "127.0.0.4"))).isInstanceOf(InvalidRequestException.class);
        assertThat(setup.ensure("B", "127.0.0.4").nasCreated()).isTrue();
        assertThat(OwnerSql.client().sql("select count(*) from network_router where tenant_id = :t")
                .param("t", ProbeTenant.ID).query(Long.class).single()).isEqualTo(2);
    }

    @Test
    @Order(2)
    void ADR0019_theProbeTenantIsInvisibleToListsLookupsAndSignIn() throws Exception {
        setup.ensure("A", "127.0.0.3");
        assertThat(tenants.list()).noneMatch(t -> t.id().equals(ProbeTenant.ID));
        assertThat(tenants.find(ProbeTenant.ID)).isEmpty();
        assertThat(tenants.findByCodeOrSubdomain(ProbeTenant.CODE)).isEmpty();
        assertThat(tenants.findByCodeOrSubdomain(ProbeTenant.SUBDOMAIN)).isEmpty();
        assertThat(OwnerSql.client().sql("select status from tenancy_tenant where id = :t").param("t", ProbeTenant.ID)
                .query(String.class).single()).isEqualTo("INTERNAL");
        // A staff sign-in naming the probe tenant finds no such ISP.
        final HttpResponse<String> signIn = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/auth/login"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"tenant\":\"" + ProbeTenant.CODE
                                + "\",\"email\":\"anyone@probe.example.test\",\"password\":\"not-a-password-fake\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(signIn.statusCode()).as(signIn.body()).isIn(400, 401, 404);
        assertThat(signIn.body()).doesNotContain("accessToken");
        // No ISP may take the probe's subdomain.
        assertThat(catchThrowable(() -> tenants.create("PRBX", "Probe Copy", ProbeTenant.SUBDOMAIN)))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    @Order(3)
    void T4_ADR0019_theWorkerReadsExactlyTheProbeLinesPasswords_andNoOtherTenantsLineCredentials() {
        setup.ensure("A", "127.0.0.3");
        // Another tenant with a line, so there is something not to see.
        final UUID other = TestTenants.create(jdbc, tx);
        final Api api = new Api(port, other);
        final String routerId = Catalog.router(api, "Other-01").json().get("router").get("id").asString();
        final String customerId = Catalog.customer(api, "Other Customer", "0711000444");
        api.post("/api/v1/subscriptions", "{\"customerId\":\"" + customerId + "\",\"packageId\":\""
                + Catalog.pppoePackage(api, "Other 10") + "\",\"routerId\":\"" + routerId + "\"}");

        final JdbcClient worker = JdbcClient.create(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), "isp_worker", "isp_worker_test_only"));
        final List<Map<String, Object>> rows = worker.sql("select username, password, expires_at from v_radius_probe_credential_v1")
                .query().listOfRows();
        final List<String> probeUsernames = OwnerSql.client()
                .sql("select pppoe_username from subscriptions_subscription where tenant_id = :t").param("t", ProbeTenant.ID)
                .query(String.class).list();
        assertThat(rows).extracting(r -> (String) r.get("username")).containsExactlyInAnyOrderElementsOf(probeUsernames);
        assertThat(rows).allSatisfy(r -> assertThat((String) r.get("password")).hasSizeGreaterThanOrEqualTo(12));

        for (final String forbidden : List.of("select secret from network_radius_credential",
                "select value from v_radius_auth_v7", "select pppoe_username from subscriptions_subscription",
                "select * from customers_customer")) {
            assertThat(catchThrowable(() -> worker.sql(forbidden).query().listOfRows())).as(forbidden)
                    .rootCause().hasMessageContaining("permission denied");
        }
        // radius_client_v3 carries router secrets only (ADR 0011): none of them is any line's password.
        final List<String> lineSecrets = OwnerSql.client()
                .sql("select secret from network_radius_credential where subscription_id is not null and secret is not null")
                .query(String.class).list();
        final List<String> clientSecrets = worker.sql("select secret from radius_client_v3").query(String.class).list();
        assertThat(clientSecrets).isNotEmpty().doesNotContainAnyElementsOf(lineSecrets);
    }

    @Test
    @Order(4)
    void S12_theDatabaseRefusesAnActivationWithoutACauseAndProbeOutsideTheProbeTenant() {
        final UUID tenant = TestTenants.create(jdbc, tx);
        final Api api = new Api(port, tenant);
        final String routerId = Catalog.router(api, "S12-01").json().get("router").get("id").asString();
        final String customerId = Catalog.customer(api, "Wairimu S12", "0711000555");
        final String line = api.post("/api/v1/subscriptions", "{\"customerId\":\"" + customerId + "\",\"packageId\":\""
                + Catalog.pppoePackage(api, "S12 10") + "\",\"routerId\":\"" + routerId + "\"}")
                .json().get("subscription").get("id").asString();
        final JdbcClient owner = OwnerSql.client();

        assertThat(catchThrowable(() -> owner.sql("update subscriptions_subscription set state = 'ACTIVE', expires_at = now() + interval '30 days' where id = :id")
                .param("id", UUID.fromString(line)).update())).hasMessageContaining("S12");
        assertThat(catchThrowable(() -> owner.sql("update subscriptions_subscription set state = 'ACTIVE', expires_at = now() + interval '30 days', activation_cause = 'PROBE' where id = :id")
                .param("id", UUID.fromString(line)).update())).hasMessageContaining("subscriptions_subscription_probe_tenant_ck");
        assertThat(catchThrowable(() -> owner.sql("update subscriptions_subscription set activation_cause = 'GIFT' where id = :id")
                .param("id", UUID.fromString(line)).update())).hasMessageContaining("subscriptions_subscription_activation_cause_ck");

        // The probe tenant's literal in the checks is ProbeTenant.ID: its lines carry PROBE and nothing refused them.
        setup.ensure("A", "127.0.0.3");
        assertThat(owner.sql("select count(*) from subscriptions_subscription where tenant_id = :t and activation_cause = 'PROBE'")
                .param("t", ProbeTenant.ID).query(Long.class).single()).isEqualTo(2);

        // A cause, once set, never changes; a renewal records RENEWAL.
        owner.sql("update subscriptions_subscription set state = 'ACTIVE', expires_at = now() + interval '30 days', activation_cause = 'RENEWAL' where id = :id")
                .param("id", UUID.fromString(line)).update();
        assertThat(catchThrowable(() -> owner.sql("update subscriptions_subscription set activation_cause = 'IMPORTED' where id = :id")
                .param("id", UUID.fromString(line)).update())).hasMessageContaining("never changes");

        // A cancel before activation (S9) needs no cause.
        final String unpaid = api.post("/api/v1/subscriptions", "{\"customerId\":\"" + customerId + "\",\"packageId\":\""
                + api.get("/api/v1/subscriptions/" + line).json().get("packageId").asString() + "\",\"routerId\":\"" + routerId + "\"}")
                .json().get("subscription").get("id").asString();
        assertThat(api.postWithKey("/api/v1/subscriptions/" + unpaid + "/cancel", UUID.randomUUID().toString(),
                "{\"reason\":\"Never paid\"}").status()).isEqualTo(200);
        assertThat(owner.sql("select state, activation_cause from subscriptions_subscription where id = :id")
                .param("id", UUID.fromString(unpaid)).query().singleRow())
                .containsEntry("state", "TERMINATED").containsEntry("activation_cause", null);
    }
}
