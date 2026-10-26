package com.corebalt.isp.customers;

import static org.assertj.core.api.Assertions.assertThat;

import com.corebalt.isp.support.Api;
import com.corebalt.isp.support.IntegrationTest;
import com.corebalt.isp.support.OwnerSql;
import com.corebalt.isp.support.TestTenants;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/** Customer API over real PostgreSQL as {@code isp_app} (T1, T2). */
class CustomersIT extends IntegrationTest {

    @Value("${local.server.port}")
    int port;

    @Autowired JdbcClient jdbc;
    @Autowired TransactionTemplate tx;

    TestTenants.Created tenant;
    Api api;

    @BeforeEach
    void tenant() {
        tenant = TestTenants.createWithCode(jdbc, tx);
        api = new Api(port, tenant.id());
    }

    @Test
    void T2_numbersAreTenantCodePlusSixDigitSequence() {
        final JsonNode first = create("Wanjiku Kamau", "0712 345 678").json();
        final JsonNode second = create("Otieno Ouma", "+254 733 000 111").json();
        assertThat(first.get("number").asString()).isEqualTo(tenant.code() + "000001");
        assertThat(second.get("number").asString()).isEqualTo(tenant.code() + "000002");
    }

    @Test
    void T2_numbersAreNeverReusedAfterArchive() {
        final JsonNode first = create("Archived Later", "0712000001").json();
        assertThat(api.post("/api/v1/customers/" + first.get("id").asString() + "/archive").json()
                        .get("status").asString())
                .isEqualTo("ARCHIVED");
        assertThat(create("Next One", "0712000002").json().get("number").asString())
                .isEqualTo(tenant.code() + "000002");
    }

    @Test
    void phoneIsStoredInE164() {
        assertThat(create("Local Form", "0712-345-678").json().get("phone").asString()).isEqualTo("+254712345678");
    }

    @Test
    void invalidPhoneIs400ProblemDetail() {
        final Api.Response response = create("Bad Phone", "12345");
        assertThat(response.status()).isEqualTo(400);
        assertThat(response.body()).contains("validation-failed").contains("\"field\":\"phone\"");
    }

    @Test
    void missingNameIs400() {
        final Api.Response response = api.post("/api/v1/customers", "{\"phone\":\"0712345678\"}");
        assertThat(response.status()).isEqualTo(400);
        assertThat(response.body()).contains("\"field\":\"name\"");
    }

    @Test
    void updateReplacesTheProfileButNotTheNumber() {
        final JsonNode created = create("Before", "0712345678").json();
        final Api.Response updated =
                api.put(
                        "/api/v1/customers/" + created.get("id").asString(),
                        "{\"name\":\"After\",\"phone\":\"0799000000\",\"address\":\"Utawala\",\"lat\":-1.2921,\"lng\":36.8219}");
        assertThat(updated.status()).isEqualTo(200);
        assertThat(updated.json().get("name").asString()).isEqualTo("After");
        assertThat(updated.json().get("number").asString()).isEqualTo(created.get("number").asString());
        assertThat(updated.json().get("lat").decimalValue()).isEqualByComparingTo("-1.2921");
    }

    /** ADR 0014, P3: the old system's account reference is kept upper-cased, unique per tenant, and found by search. */
    @Test
    void P3_anOldSystemReferenceIsKeptUpperCasedUniqueAndSearchable() {
        final Api.Response created = api.post("/api/v1/customers",
                "{\"name\":\"Moved In\",\"phone\":\"0712000001\",\"legacyAccountRef\":\" hse-4 \"}");
        assertThat(created.status()).as(created.body()).isEqualTo(201);
        assertThat(created.json().get("legacyAccountRef").asString()).isEqualTo("HSE-4");
        final Api.Response duplicate = api.post("/api/v1/customers",
                "{\"name\":\"Same Ref\",\"phone\":\"0712000002\",\"legacyAccountRef\":\"Hse-4\"}");
        assertThat(duplicate.status()).isEqualTo(400);
        assertThat(duplicate.body()).contains("legacyAccountRef").contains("already used by customer " + created.json().get("number").asString());
        assertThat(api.post("/api/v1/customers", "{\"name\":\"Bad Ref\",\"phone\":\"0712000003\",\"legacyAccountRef\":\"no!way\"}").status())
                .isEqualTo(400);
        assertThat(api.get("/api/v1/customers?q=hse").json().get("total").asLong()).isOne();
        final Api.Response cleared = api.put("/api/v1/customers/" + created.json().get("id").asString(),
                "{\"name\":\"Moved In\",\"phone\":\"0712000001\",\"legacyAccountRef\":\"\"}");
        assertThat(cleared.status()).isEqualTo(200);
        assertThat(cleared.json().get("legacyAccountRef").isNull()).isTrue();
        // Another tenant may use the same old reference.
        final Api other = new Api(port, TestTenants.create(jdbc, tx));
        assertThat(other.post("/api/v1/customers", "{\"name\":\"Elsewhere\",\"phone\":\"0712000001\",\"legacyAccountRef\":\"HSE-4\"}").status())
                .isEqualTo(201);
    }

    @Test
    void listSearchesNameNumberAndPhoneWithPaging() {
        create("Achieng Atieno", "0711111111");
        create("Baraka Mwangi", "0722222222");
        create("Chebet Kiprop", "0733333333");

        final JsonNode byName = api.get("/api/v1/customers?q=baraka").json();
        assertThat(byName.get("total").asLong()).isEqualTo(1);
        assertThat(byName.get("items").get(0).get("name").asString()).isEqualTo("Baraka Mwangi");

        assertThat(api.get("/api/v1/customers?q=0733333").json().get("total").asLong()).isEqualTo(1);
        assertThat(api.get("/api/v1/customers?q=" + tenant.code() + "00000").json().get("total").asLong())
                .isEqualTo(3);

        final JsonNode page = api.get("/api/v1/customers?size=2&page=1&sort=name,desc").json();
        assertThat(page.get("total").asLong()).isEqualTo(3);
        assertThat(page.get("size").asInt()).isEqualTo(2);
        assertThat(page.get("items").get(0).get("name").asString()).isEqualTo("Achieng Atieno");
    }

    @Test
    void sizeAbove200AndUnknownSortAre400() {
        assertThat(api.get("/api/v1/customers?size=201").status()).isEqualTo(400);
        assertThat(api.get("/api/v1/customers?sort=phone").status()).isEqualTo(400);
    }

    @Test
    void T1_anotherTenantsCustomerIsNotFound() {
        final String id = create("Mine", "0712345678").json().get("id").asString();
        final Api other = new Api(port, TestTenants.create(jdbc, tx));
        assertThat(other.get("/api/v1/customers/" + id).status()).isEqualTo(404);
        assertThat(other.get("/api/v1/customers").json().get("total").asLong()).isZero();
    }

    @Test
    void E1_registrationWritesCustomerCreatedToTheOutbox() {
        final String id = create("Evented", "0712345678").json().get("id").asString();
        final Long events =
                OwnerSql.client()
                        .sql("select count(*) from outbox_event where event_type = 'CustomerCreated' and payload ->> 'customerId' = :id")
                        .param("id", id)
                        .query(Long.class)
                        .single();
        assertThat(events).isOne();
    }

    @Test
    void unknownIdIs404() {
        assertThat(api.get("/api/v1/customers/" + UUID.randomUUID()).status()).isEqualTo(404);
    }

    private Api.Response create(final String name, final String phone) {
        return api.post("/api/v1/customers", "{\"name\":\"" + name + "\",\"phone\":\"" + phone + "\"}");
    }
}
