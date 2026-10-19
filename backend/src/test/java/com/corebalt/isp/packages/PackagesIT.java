package com.corebalt.isp.packages;

import static org.assertj.core.api.Assertions.assertThat;

import com.corebalt.isp.support.Api;
import com.corebalt.isp.support.IntegrationTest;
import com.corebalt.isp.support.OwnerSql;
import com.corebalt.isp.support.TestTenants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/** Package catalogue API (docs/03 packages, B2, T1). */
class PackagesIT extends IntegrationTest {

    static final String HOME_10 =
            """
            {"name":"Home 10","downloadKbps":10000,"uploadKbps":5000,"durationMinutes":43200,
             "price":{"amount":250000,"currency":"KES"},"throttleDownloadKbps":2000,"throttleUploadKbps":1000}
            """;

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
    void B2_createStoresPriceInMinorUnitsWithDefaults() {
        final Api.Response created = api.post("/api/v1/packages", HOME_10);
        assertThat(created.status()).isEqualTo(201);
        final JsonNode pkg = created.json();
        assertThat(pkg.get("price").get("amount").asLong()).isEqualTo(250000);
        assertThat(pkg.get("price").get("currency").asString()).isEqualTo("KES");
        assertThat(pkg.get("type").asString()).isEqualTo("PPPOE");
        assertThat(pkg.get("billingMode").asString()).isEqualTo("PREPAID");
        assertThat(pkg.get("active").asBoolean()).isTrue();
    }

    @Test
    void namesAreUniquePerTenantIgnoringCase() {
        api.post("/api/v1/packages", HOME_10);
        final Api.Response duplicate = api.post("/api/v1/packages", HOME_10.replace("Home 10", "HOME 10"));
        assertThat(duplicate.status()).isEqualTo(409);
        assertThat(duplicate.body()).contains("https://problems.corebalt.app/conflict");
        // Another tenant may use the same name.
        assertThat(new Api(port, TestTenants.create(jdbc, tx)).post("/api/v1/packages", HOME_10).status())
                .isEqualTo(201);
    }

    @Test
    void throttleSpeedsComeAsAPair() {
        final Api.Response response =
                api.post("/api/v1/packages", HOME_10.replace(",\"throttleUploadKbps\":1000", ""));
        assertThat(response.status()).isEqualTo(400);
        assertThat(response.body()).contains("throttle speeds come as a pair");
    }

    @Test
    void unknownCurrencyAndTypeAre400() {
        assertThat(api.post("/api/v1/packages", HOME_10.replace("KES", "XQQ")).status()).isEqualTo(400);
        assertThat(api.post("/api/v1/packages", HOME_10.replace("\"name\"", "\"type\":\"FIBRE\",\"name\"")).status())
                .isEqualTo(400);
    }

    @Test
    void reviseDeactivateAndReactivatePublishPackageChanged() {
        final String id = api.post("/api/v1/packages", HOME_10).json().get("id").asString();

        final Api.Response revised = api.put("/api/v1/packages/" + id, HOME_10.replace("250000", "300000"));
        assertThat(revised.json().get("price").get("amount").asLong()).isEqualTo(300000);
        assertThat(api.post("/api/v1/packages/" + id + "/deactivate").json().get("active").asBoolean()).isFalse();
        assertThat(api.get("/api/v1/packages?active=true").json().get("total").asLong()).isZero();
        assertThat(api.post("/api/v1/packages/" + id + "/activate").json().get("active").asBoolean()).isTrue();

        final var changes =
                OwnerSql.client()
                        .sql("select payload ->> 'change' from outbox_event where payload ->> 'packageId' = :id order by occurred_at, id")
                        .param("id", id)
                        .query(String.class)
                        .list();
        assertThat(changes).containsExactly("CREATED", "REVISED", "DEACTIVATED", "REACTIVATED");
    }

    @Test
    void typeCannotChangeOnRevise() {
        final String id = api.post("/api/v1/packages", HOME_10).json().get("id").asString();
        assertThat(api.put("/api/v1/packages/" + id, HOME_10.replace("\"name\"", "\"type\":\"HOTSPOT\",\"name\"")).status())
                .isEqualTo(400);
    }
}
