package com.corebalt.isp;

import static org.assertj.core.api.Assertions.assertThat;

import com.corebalt.isp.support.Api;
import com.corebalt.isp.support.Catalog;
import com.corebalt.isp.support.IntegrationTest;
import com.corebalt.isp.support.OwnerSql;
import com.corebalt.isp.support.TestTenants;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/** Server-side filters of the staff lists (conventions.md: tables page, sort and filter on the server). */
class ListFiltersIT extends IntegrationTest {

    @Value("${local.server.port}")
    int port;

    @Autowired JdbcClient jdbc;
    @Autowired TransactionTemplate tx;

    @Test
    void linesFilterByStoredStateRouterAndPackage() {
        final Api api = new Api(port, TestTenants.create(jdbc, tx));
        final String home = Catalog.pppoePackage(api, "Home 10");
        final String biz = Catalog.pppoePackage(api, "Biz 10");
        final String r1 = Catalog.router(api, "Nyeri-01").json().get("router").get("id").asString();
        final String r2 = Catalog.router(api, "Nyeri-02").json().get("router").get("id").asString();
        final String c = Catalog.customer(api, "Wambui Njoroge", "0711222333");
        open(api, c, home, r1);
        open(api, c, biz, r2);

        assertThat(api.get("/api/v1/subscriptions?routerId=" + r1).json().get("total").asLong()).isOne();
        assertThat(api.get("/api/v1/subscriptions?packageId=" + biz).json().get("total").asLong()).isOne();
        assertThat(api.get("/api/v1/subscriptions?state=PENDING").json().get("total").asLong()).isEqualTo(2);
        assertThat(api.get("/api/v1/subscriptions?state=ACTIVE").json().get("total").asLong()).isZero();
        assertThat(api.get("/api/v1/subscriptions?state=EXPIRING").status()).isEqualTo(400);
        assertThat(api.get("/api/v1/subscriptions?displayStatus=PENDING&routerId=" + r2).json().get("total").asLong()).isOne();
        assertThat(api.get("/api/v1/subscriptions?displayStatus=RESTRICTED").json().get("total").asLong()).isZero();
        // S1: a line stored ACTIVE whose expiry and grace have passed is listed as RESTRICTED, derived in SQL.
        OwnerSql.client().sql("""
                        update subscriptions_subscription set state = 'ACTIVE', expires_at = now() - interval '5 days',
                            grace_days = 1, activated_at = now() - interval '35 days'
                        where router_id = :r
                        """)
                .param("r", UUID.fromString(r1)).update();
        final var restricted = api.get("/api/v1/subscriptions?displayStatus=RESTRICTED").json();
        assertThat(restricted.get("total").asLong()).isOne();
        assertThat(restricted.get("items").get(0).get("displayStatus").asString()).isEqualTo("RESTRICTED");
        assertThat(api.get("/api/v1/subscriptions?displayStatus=SOON").status()).isEqualTo(400);
        assertThat(api.get("/api/v1/subscriptions?sort=expiresAt,desc").status()).isEqualTo(200);
    }

    @Test
    void paymentsFilterByStatusChannelAndReceivedTime() {
        final Api api = new Api(port, TestTenants.create(jdbc, tx));
        final String c = Catalog.customer(api, "Kiprop Rotich", "0722333444");
        manual(api, c, "CASH", "R-1", "2026-10-01T08:00:00Z");
        manual(api, c, "BANK", "R-2", "2026-10-05T08:00:00Z");

        assertThat(api.get("/api/v1/payments?channel=BANK").json().get("total").asLong()).isOne();
        // A matched payment names its customer, so the list can show who paid.
        final var bank = api.get("/api/v1/payments?channel=BANK").json().get("items").get(0);
        assertThat(bank.get("customerName").asString()).isEqualTo("Kiprop Rotich");
        assertThat(bank.get("customerNumber").asString()).isNotBlank();
        assertThat(api.get("/api/v1/payments?status=MATCHED").json().get("total").asLong()).isEqualTo(2);
        assertThat(api.get("/api/v1/payments?receivedFrom=2026-10-02T00:00:00Z").json().get("total").asLong()).isOne();
        assertThat(api.get("/api/v1/payments?receivedBefore=2026-10-05T08:00:00Z").json().get("total").asLong()).isOne();
        assertThat(api.get("/api/v1/payments?status=PAID").status()).isEqualTo(400);
    }

    private static void open(final Api api, final String customer, final String pkg, final String router) {
        assertThat(api.post("/api/v1/subscriptions", "{\"customerId\":\"" + customer + "\",\"packageId\":\"" + pkg
                + "\",\"routerId\":\"" + router + "\"}").status()).isEqualTo(201);
    }

    private static void manual(final Api api, final String customer, final String channel, final String ref, final String at) {
        assertThat(api.postWithKey("/api/v1/payments/manual", UUID.randomUUID().toString(), "{\"customerId\":\"" + customer
                + "\",\"amount\":{\"amount\":10000,\"currency\":\"KES\"},\"channel\":\"" + channel + "\",\"reference\":\""
                + ref + "\",\"receivedAt\":\"" + Instant.parse(at) + "\"}").status()).isEqualTo(201);
    }
}
