package com.corebalt.isp.subscriptions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.corebalt.isp.shared.tenant.TenantContext;
import com.corebalt.isp.support.Api;
import com.corebalt.isp.support.Catalog;
import com.corebalt.isp.support.IntegrationTest;
import com.corebalt.isp.support.OwnerSql;
import com.corebalt.isp.support.TestTenants;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Opening lines and their PPPoE passwords (T2, T3, T4 with the ADR 0009 exception, N2), and the
 * column grants that keep the application from ever reading a password back.
 */
class LinesIT extends IntegrationTest {

    @Value("${local.server.port}")
    int port;

    @Autowired JdbcClient jdbc;
    @Autowired TransactionTemplate tx;

    TestTenants.Created tenant;
    Api api;
    String packageId;
    String routerId;
    String customerId;
    String customerNumber;

    @BeforeEach
    void catalog() {
        tenant = TestTenants.createWithCode(jdbc, tx);
        api = new Api(port, tenant.id());
        packageId = Catalog.pppoePackage(api, "Home 10");
        routerId = Catalog.router(api, "Utawala-01").json().get("router").get("id").asString();
        customerId = Catalog.customer(api, "Wanjiku Kamau", "0712345678");
        customerNumber = api.get("/api/v1/customers/" + customerId).json().get("number").asString();
    }

    @Test
    void T2_firstLineUsesTheCustomerNumberAndLaterLinesAreSuffixed() {
        final JsonNode first = open(customerId).json();
        final JsonNode second = open(customerId).json();
        assertThat(first.get("subscription").get("pppoeUsername").asString()).isEqualTo(customerNumber);
        assertThat(second.get("subscription").get("pppoeUsername").asString()).isEqualTo(customerNumber + "-2");
        assertThat(first.get("subscription").get("state").asString()).isEqualTo("PENDING");
    }

    @Test
    void T4_passwordIsShownOnceAtCreationAndNeverReturnedAgain() {
        final Api.Response opened = open(customerId);
        assertThat(opened.status()).isEqualTo(201);
        assertThat(opened.header("Cache-Control")).contains("no-store");
        final String password = opened.json().get("pppoePassword").asString();
        assertThat(password).hasSize(16).matches("[A-Za-z2-9]+");

        final String id = opened.json().get("subscription").get("id").asString();
        assertThat(api.get("/api/v1/subscriptions/" + id).body()).doesNotContain(password).doesNotContain("assword");
        assertThat(api.get("/api/v1/subscriptions?customerId=" + customerId).body()).doesNotContain(password);
    }

    @Test
    void N2_theGeneratedPasswordIsWhatFreeRadiusChecks() {
        final JsonNode opened = open(customerId).json();
        // PENDING lines are rejected without a password check; read the stored value through the
        // view as it will be once the line is active (step 4 activates lines).
        activate(opened.get("subscription").get("id").asString());
        assertThat(radiusPassword(customerNumber)).isEqualTo(opened.get("pppoePassword").asString());
    }

    @Test
    void T4_regenerateReplacesThePasswordAndIsAuditedWithoutIt() {
        final JsonNode opened = open(customerId).json();
        final String id = opened.get("subscription").get("id").asString();
        activate(id);

        final Api.Response regenerated = api.post("/api/v1/subscriptions/" + id + "/pppoe-password");
        assertThat(regenerated.status()).isEqualTo(200);
        assertThat(regenerated.header("Cache-Control")).contains("no-store");
        final String newPassword = regenerated.json().get("pppoePassword").asString();
        assertThat(newPassword).isNotEqualTo(opened.get("pppoePassword").asString());
        assertThat(regenerated.json().get("pppoeUsername").asString()).isEqualTo(customerNumber);
        assertThat(radiusPassword(customerNumber)).isEqualTo(newPassword);

        final List<String> audit =
                OwnerSql.client()
                        .sql("select action || ' ' || coalesce(after_state::text, '') from identity_audit_log where target_id = cast(:id as uuid) order by occurred_at, action")
                        .param("id", id)
                        .query(String.class)
                        .list();
        assertThat(audit).hasSize(3);
        assertThat(audit.get(0)).startsWith("network.pppoe_password_issued");
        assertThat(audit.get(1)).startsWith("subscriptions.line_opened");
        assertThat(audit.get(2)).startsWith("network.pppoe_password_regenerated");
        assertThat(String.join(" ", audit))
                .doesNotContain(newPassword)
                .doesNotContain(opened.get("pppoePassword").asString());
    }

    @Test
    void ADR0009_applicationRoleCanSetButNeverReadACredential() {
        final String id = open(customerId).json().get("subscription").get("id").asString();
        TenantContext.runAsTenant(tenant.id(), () -> {
            assertDenied("select secret from network_radius_credential");
            assertDenied("select * from network_radius_credential");
            assertDenied("update network_radius_credential set secret = secret where subscription_id = '" + id + "'");
            assertDenied("delete from network_radius_credential");
            assertDenied("select count(*) from v_radius_auth_v3");
            assertDenied("select count(*) from v_radius_auth_v4");
            assertDenied("select count(*) from v_radius_auth_v5");
            assertDenied("select count(*) from v_radius_auth_v6");
            assertDenied("select count(*) from v_radius_auth_v7");
            assertDenied("select count(*) from radius_client_v3");
            // The keyed update the network module uses works, and only on this tenant's rows (RLS).
            final Integer updated =
                    tx.execute(s -> jdbc.sql("update network_radius_credential set secret = 'replaced-by-test-1', updated_at = now() where subscription_id = cast(:id as uuid)")
                            .param("id", id)
                            .update());
            assertThat(updated).isOne();
            final Long keys =
                    tx.execute(s -> jdbc.sql("select count(*) from network_radius_credential where subscription_id = cast(:id as uuid)")
                            .param("id", id)
                            .query(Long.class)
                            .single());
            assertThat(keys).isOne();
        });
    }

    @Test
    void T3_lineCannotUseAnotherTenantsRouterOrPackage() {
        final Api other = new Api(port, TestTenants.create(jdbc, tx));
        final String foreignRouter = Catalog.router(other, "Theirs").json().get("router").get("id").asString();
        final String foreignPackage = Catalog.pppoePackage(other, "Theirs");
        assertThat(api.post("/api/v1/subscriptions", line(customerId, packageId, foreignRouter)).status()).isEqualTo(404);
        assertThat(api.post("/api/v1/subscriptions", line(customerId, foreignPackage, routerId)).status()).isEqualTo(404);
        assertThat(other.post("/api/v1/subscriptions", line(customerId, foreignPackage, foreignRouter)).status())
                .isEqualTo(404);
    }

    @Test
    void archivedCustomersAndInactivePackagesGetNoNewLines() {
        api.post("/api/v1/packages/" + packageId + "/deactivate");
        assertThat(open(customerId).status()).isEqualTo(409);
        api.post("/api/v1/packages/" + packageId + "/activate");

        api.post("/api/v1/customers/" + customerId + "/archive");
        final Api.Response response = open(customerId);
        assertThat(response.status()).isEqualTo(409);
        assertThat(response.body()).contains("archived");
    }

    @Test
    void anotherTenantCannotRegenerateThePassword() {
        final String id = open(customerId).json().get("subscription").get("id").asString();
        final Api other = new Api(port, TestTenants.create(jdbc, tx));
        assertThat(other.post("/api/v1/subscriptions/" + id + "/pppoe-password").status()).isEqualTo(404);
        assertThat(other.get("/api/v1/subscriptions/" + UUID.fromString(id)).status()).isEqualTo(404);
    }

    /** Runs one statement as isp_app in a tenant transaction and expects PostgreSQL to refuse it. */
    private void assertDenied(final String sql) {
        assertThatThrownBy(() -> tx.execute(s -> jdbc.sql(sql).query().listOfRows()))
                .as(sql)
                .rootCause()
                .hasMessageContaining("permission denied");
    }

    private Api.Response open(final String customer) {
        return api.post("/api/v1/subscriptions", line(customer, packageId, routerId));
    }

    private static String line(final String customer, final String pkg, final String router) {
        return "{\"customerId\":\"" + customer + "\",\"packageId\":\"" + pkg + "\",\"routerId\":\"" + router + "\"}";
    }

    /** Lifecycle is step 4; make the line ACTIVE directly so the view returns its password. */
    private static void activate(final String subscriptionId) {
        OwnerSql.client()
                .sql("update subscriptions_subscription set state = 'ACTIVE', expires_at = now() + interval '30 days' where id = cast(:id as uuid)")
                .param("id", subscriptionId)
                .update();
    }

    private static String radiusPassword(final String username) {
        return OwnerSql.client()
                .sql("select distinct value from v_radius_auth_v3 where username = :username and attribute = 'Cleartext-Password'")
                .param("username", username)
                .query(String.class)
                .single();
    }
}
