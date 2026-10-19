package com.corebalt.isp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.corebalt.isp.billing.application.WalletService;
import com.corebalt.isp.shared.money.Money;
import com.corebalt.isp.shared.outbox.OutboxRelay;
import com.corebalt.isp.shared.tenant.TenantContext;
import com.corebalt.isp.subscriptions.api.RenewalResultDto;
import com.corebalt.isp.subscriptions.application.RenewalService;
import com.corebalt.isp.support.Api;
import com.corebalt.isp.support.Catalog;
import com.corebalt.isp.support.IntegrationTest;
import com.corebalt.isp.support.OwnerSql;
import com.corebalt.isp.support.TestTenants;
import com.corebalt.isp.support.TestTokens;
import java.time.Duration;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * The customer portal API (docs/09 step 8): a CUSTOMER token sees only its own customer's data. Two
 * customers of one tenant share row-level security, so every test checks that one cannot reach the
 * other's lines, usage, payments, receipts or STK requests (T1; the customer comes from the token only).
 */
class PortalIT extends IntegrationTest {

    @Value("${local.server.port}")
    int port;

    @Autowired JdbcClient jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired RenewalService renewals;
    @Autowired WalletService wallets;
    @Autowired OutboxRelay relay;

    UUID tenant;
    Api staff;
    Customer mary;
    Customer otieno;

    record Customer(UUID id, UUID lineId, String username, Api portal) {}

    @BeforeEach
    void twoCustomers() {
        tenant = TestTenants.create(jdbc, tx);
        staff = new Api(port, tenant);
        final String packageId = Catalog.pppoePackage(staff, "Home 10");
        final String routerId = Catalog.router(staff, "Juja-01").json().get("router").get("id").asString();
        assertThat(staff.put("/api/v1/settings/mpesa", """
                {"shortcode":"600984","shortcodeType":"PAYBILL","sandbox":true,
                 "consumerKey":"fake-key","consumerSecret":"fake-secret","passkey":"fake-passkey"}
                """).status()).isEqualTo(200);
        mary = customer("Mary Wairimu", "0711000333", packageId, routerId);
        otieno = customer("Otieno Ouma", "0722000444", packageId, routerId);
    }

    @Test
    void T1_aCustomerSeesOnlyTheirOwnRecordAndLines() {
        final JsonNode me = mary.portal().get("/api/v1/portal/customer").json();
        assertThat(me.get("id").asString()).isEqualTo(mary.id().toString());

        final JsonNode lines = mary.portal().get("/api/v1/portal/lines").json().get("lines");
        assertThat(lines).singleElement().satisfies(line -> {
            assertThat(line.get("id").asString()).isEqualTo(mary.lineId().toString());
            assertThat(line.get("packageName").asString()).isEqualTo("Home 10");
            assertThat(line.get("displayStatus").asString()).isEqualTo("ACTIVE");
            assertThat(line.get("accessProfile").asString()).isEqualTo("FULL");
            assertThat(line.get("renewalPrice").get("amount").asLong()).isEqualTo(250_000);
            // B5: the renewal emptied the wallet, so the next one needs the full price.
            assertThat(line.get("payToRenew").get("amount").asLong()).isEqualTo(250_000);
            assertThat(line.get("due").asBoolean()).isFalse();
        });
    }

    @Test
    void B5_payAllCoversEveryDueLineOnceAgainstTheSharedWallet() {
        final String packageId = OwnerSql.client().sql("select package_id::text from subscriptions_subscription where id = :id")
                .param("id", mary.lineId()).query(String.class).single();
        final String routerId = OwnerSql.client().sql("select router_id::text from subscriptions_subscription where id = :id")
                .param("id", mary.lineId()).query(String.class).single();
        // A second line (PENDING, due) and the first one expiring within 3 days (EXPIRING, due).
        assertThat(staff.post("/api/v1/subscriptions", "{\"customerId\":\"" + mary.id() + "\",\"packageId\":\"" + packageId
                + "\",\"routerId\":\"" + routerId + "\"}").status()).isEqualTo(201);
        OwnerSql.client().sql("update subscriptions_subscription set expires_at = now() + interval '1 day' where id = :id")
                .param("id", mary.lineId()).update();
        fund(mary, 100_000);

        final JsonNode portal = mary.portal().get("/api/v1/portal/lines").json();
        assertThat(portal.get("lines")).hasSize(2).allSatisfy(line -> {
            assertThat(line.get("due").asBoolean()).isTrue();
            assertThat(line.get("payToRenew").get("amount").asLong()).isEqualTo(150_000);
        });
        assertThat(portal.get("balance").get("amount").asLong()).isEqualTo(100_000);
        assertThat(portal.get("payAll").get("amount").asLong()).isEqualTo(2 * 250_000 - 100_000);
    }

    @Test
    void B5_payToRenewIsWhatTheWalletStillLacks() {
        fund(mary, 100_000);
        final JsonNode line = mary.portal().get("/api/v1/portal/lines").json().get("lines").get(0);
        assertThat(line.get("payToRenew").get("amount").asLong()).isEqualTo(150_000);
        assertThat(mary.portal().get("/api/v1/portal/wallet").json().get("balance").get("amount").asLong()).isEqualTo(100_000);
    }

    @Test
    void N7_usageComesFromTheDailyTableAndOnlyForOwnLines() {
        final LocalDate today = LocalDate.now();
        OwnerSql.client().sql("""
                        insert into network_usage_daily (tenant_id, subscription_id, day, input_octets, output_octets, updated_at)
                        values (:t, :s, :d1, 100, 2000, now()), (:t, :s, :d2, 300, 4000, now())
                        """)
                .param("t", tenant).param("s", mary.lineId()).param("d1", today.minusDays(1)).param("d2", today)
                .update();

        final JsonNode usage = mary.portal().get("/api/v1/portal/lines/" + mary.lineId() + "/usage?from="
                + today.minusDays(6) + "&to=" + today).json();
        assertThat(usage).hasSize(2);
        assertThat(usage.get(1).get("outputOctets").asLong()).isEqualTo(4000);

        assertThat(mary.portal().get("/api/v1/portal/lines/" + otieno.lineId() + "/usage").status()).isEqualTo(404);
        assertThat(mary.portal().get("/api/v1/portal/lines/" + mary.lineId() + "/usage?from=" + today + "&to="
                + today.minusDays(1)).status()).isEqualTo(400);
    }

    @Test
    void P4_B7_paymentsStatementAndReceiptsAreTheCustomersOwn() {
        manualPayment(mary, "CASH-M1", 50_000);
        manualPayment(otieno, "CASH-O1", 70_000);
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(300)).untilAsserted(() -> {
            TenantContext.callAsTenant(tenant, relay::relayTenant);
            assertThat(mary.portal().get("/api/v1/portal/receipts").json().get("total").asLong()).isOne();
        });

        final JsonNode payments = mary.portal().get("/api/v1/portal/payments").json();
        assertThat(payments.get("total").asLong()).isOne();
        assertThat(payments.get("items").get(0).get("reference").asString()).isEqualTo("CASH-M1");
        assertThat(mary.portal().get("/api/v1/portal/receipts").json().get("items").get(0).get("customerId").asString())
                .isEqualTo(mary.id().toString());
        final JsonNode entries = mary.portal().get("/api/v1/portal/wallet/entries").json();
        assertThat(entries.get("items")).isNotEmpty().allSatisfy(e -> assertThat(e.get("reason").isNull()).isTrue());
        // Asking for someone else's data by parameter changes nothing: the customer comes from the token.
        assertThat(mary.portal().get("/api/v1/portal/payments?customerId=" + otieno.id()).json().get("total").asLong())
                .isOne();
    }

    @Test
    void P6_payNowPushesToTheCustomersPhoneIsIdempotentAndPrivate() {
        final String key = UUID.randomUUID().toString();
        final String body = "{\"amount\":{\"amount\":250000,\"currency\":\"KES\"}}";
        final Api.Response pushed = mary.portal().postWithKey("/api/v1/portal/stk", key, body);
        assertThat(pushed.status()).as(pushed.body()).isEqualTo(202);
        assertThat(pushed.json().get("customerId").asString()).isEqualTo(mary.id().toString());
        final String stkId = pushed.json().get("id").asString();

        final Api.Response replay = mary.portal().postWithKey("/api/v1/portal/stk", key, body);
        assertThat(replay.header("Idempotent-Replayed")).isEqualTo("true");
        assertThat(replay.json().get("id").asString()).isEqualTo(stkId);
        // The same key from another customer of the tenant is refused, never answered with Mary's push.
        assertThat(otieno.portal().postWithKey("/api/v1/portal/stk", key, body).status()).isEqualTo(422);
        assertThat(mary.portal().post("/api/v1/portal/stk", body).status()).isEqualTo(400);

        assertThat(mary.portal().get("/api/v1/portal/stk/" + stkId).status()).isEqualTo(200);
        assertThat(otieno.portal().get("/api/v1/portal/stk/" + stkId).status()).isEqualTo(404);
    }

    @Test
    void ADR0010_tokenTypesKeepToTheirOwnApi() {
        assertThat(staff.get("/api/v1/portal/lines").status()).isEqualTo(403);
        assertThat(mary.portal().get("/api/v1/customers").status()).isEqualTo(403);
        assertThat(mary.portal().get("/api/v1/dashboard").status()).isEqualTo(403);
        assertThat(mary.portal().get("/api/v1/subscriptions/" + mary.lineId()).status()).isEqualTo(403);
    }

    private Customer customer(final String name, final String phone, final String packageId, final String routerId) {
        final UUID id = UUID.fromString(Catalog.customer(staff, name, phone));
        final JsonNode opened = staff.post("/api/v1/subscriptions", "{\"customerId\":\"" + id + "\",\"packageId\":\""
                + packageId + "\",\"routerId\":\"" + routerId + "\"}").json();
        final UUID lineId = UUID.fromString(opened.get("subscription").get("id").asString());
        TenantContext.runAsTenant(tenant, () -> tx.executeWithoutResult(
                s -> wallets.adjust(id, Money.ofMinor(250_000, "KES"), "test funding")));
        assertThat(TenantContext.callAsTenant(tenant, () -> renewals.renew(lineId)).outcome())
                .isEqualTo(RenewalResultDto.Outcome.RENEWED);
        OwnerSql.client().sql("insert into identity_customer_credential (tenant_id, customer_id) values (:t, :c)")
                .param("t", tenant).param("c", id).update();
        return new Customer(id, lineId, opened.get("subscription").get("pppoeUsername").asString(),
                new Api(port, TestTokens.customer(id, tenant)));
    }

    private void fund(final Customer customer, final long minor) {
        TenantContext.runAsTenant(tenant, () -> tx.executeWithoutResult(
                s -> wallets.adjust(customer.id(), Money.ofMinor(minor, "KES"), "test funding")));
    }

    private void manualPayment(final Customer customer, final String reference, final long minor) {
        final Api.Response recorded = staff.postWithKey("/api/v1/payments/manual", UUID.randomUUID().toString(),
                "{\"customerId\":\"" + customer.id() + "\",\"amount\":{\"amount\":" + minor
                        + ",\"currency\":\"KES\"},\"channel\":\"CASH\",\"reference\":\"" + reference + "\"}");
        assertThat(recorded.status()).as(recorded.body()).isEqualTo(201);
    }
}
