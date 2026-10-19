package com.corebalt.isp.subscriptions;

import static org.assertj.core.api.Assertions.assertThat;

import com.corebalt.isp.support.Api;
import com.corebalt.isp.support.Catalog;
import com.corebalt.isp.support.IntegrationTest;
import com.corebalt.isp.support.TestTenants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/** CSV import during onboarding (docs/07 checklist step 4): all rows or none, credentials shown once. */
class CustomerImportIT extends IntegrationTest {

    @Value("${local.server.port}")
    int port;

    @Autowired JdbcClient jdbc;
    @Autowired TransactionTemplate tx;

    TestTenants.Created tenant;
    Api api;
    String routerId;

    @BeforeEach
    void catalog() {
        tenant = TestTenants.createWithCode(jdbc, tx);
        api = new Api(port, tenant.id());
        Catalog.pppoePackage(api, "Home 10");
        routerId = Catalog.router(api, "Utawala-01").json().get("router").get("id").asString();
    }

    @Test
    void T2_importsCustomersInFileOrderWithLinesAndOneTimePasswords() {
        final String csv =
                """
                name,phone,email,package,installation_location,lat,lng
                Wanjiku Kamau,0712 345 678,wanjiku@example.test,Home 10,"Utawala, house 4",-1.2921,36.8219
                "Otieno ""Oti"" Ouma",+254733000111,,,,,
                """;
        final Api.Response response = api.postCsv("/api/v1/customer-imports?routerId=" + routerId, csv);
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        assertThat(response.header("Cache-Control")).contains("no-store");

        final JsonNode result = response.json();
        assertThat(result.get("imported").asInt()).isEqualTo(2);
        final JsonNode first = result.get("rows").get(0);
        assertThat(first.get("number").asString()).isEqualTo(tenant.code() + "000001");
        assertThat(first.get("pppoeUsername").asString()).isEqualTo(tenant.code() + "000001");
        assertThat(first.get("pppoePassword").asString()).hasSize(16);
        final JsonNode second = result.get("rows").get(1);
        assertThat(second.get("name").asString()).isEqualTo("Otieno \"Oti\" Ouma");
        assertThat(second.get("pppoeUsername").isNull()).isTrue();

        final JsonNode stored = api.get("/api/v1/customers/" + first.get("customerId").asString()).json();
        assertThat(stored.get("phone").asString()).isEqualTo("+254712345678");
        assertThat(stored.get("installationLocation").asString()).isEqualTo("Utawala, house 4");
        assertThat(api.get("/api/v1/subscriptions").json().get("total").asLong()).isOne();
    }

    @Test
    void anyInvalidRowRejectsTheWholeFileWithEveryRowError() {
        Catalog.customer(api, "Existing", "0799000000");
        final String csv =
                """
                name,phone,package
                Good Row,0711000001,Home 10
                ,0711000002,
                Bad Phone,12345,
                Duplicate,0711000001,
                Taken,0799 000 000,
                Unknown Package,0711000003,Fibre 100
                """;
        final Api.Response response = api.postCsv("/api/v1/customer-imports?routerId=" + routerId, csv);
        assertThat(response.status()).isEqualTo(400);
        assertThat(response.body())
                .contains("{\"row\":2,\"field\":\"name\"")
                .contains("{\"row\":3,\"field\":\"phone\",\"message\":\"not a valid phone number\"}")
                .contains("{\"row\":4,\"field\":\"phone\",\"message\":\"same number as row 1\"}")
                .contains("{\"row\":5,\"field\":\"phone\",\"message\":\"already belongs to a customer\"}")
                .contains("{\"row\":6,\"field\":\"package\"");
        // Nothing was imported: only the pre-existing customer, and no lines.
        assertThat(api.get("/api/v1/customers").json().get("total").asLong()).isOne();
        assertThat(api.get("/api/v1/subscriptions").json().get("total").asLong()).isZero();
    }

    @Test
    void rowsWithAPackageNeedARouter() {
        final Api.Response response = api.postCsv("/api/v1/customer-imports", "name,phone,package\nA,0711000001,Home 10\n");
        assertThat(response.status()).isEqualTo(400);
        assertThat(response.body()).contains("\"field\":\"routerId\"");
    }

    @Test
    void headerMustNameKnownColumnsIncludingNameAndPhone() {
        final Api.Response response = api.postCsv("/api/v1/customer-imports", "fullname,mobile\nA,0711000001\n");
        assertThat(response.status()).isEqualTo(400);
        assertThat(response.body())
                .contains("{\"row\":0,\"field\":\"fullname\"")
                .contains("{\"row\":0,\"field\":\"name\",\"message\":\"column is required\"}");
    }

    @Test
    void emptyFileIs400() {
        assertThat(api.postCsv("/api/v1/customer-imports", "").status()).isEqualTo(400);
        assertThat(api.postCsv("/api/v1/customer-imports", "name,phone\n").status()).isEqualTo(400);
    }
}
