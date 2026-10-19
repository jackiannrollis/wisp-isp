package com.corebalt.isp.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.corebalt.isp.billing.application.BillingChecks;
import com.corebalt.isp.billing.application.WalletService;
import com.corebalt.isp.payments.api.PaymentReceived;
import com.corebalt.isp.shared.id.UuidV7;
import com.corebalt.isp.shared.outbox.OutboxRelay;
import com.corebalt.isp.shared.tenant.TenantContext;
import com.corebalt.isp.support.Api;
import com.corebalt.isp.support.Catalog;
import com.corebalt.isp.support.IntegrationTest;
import com.corebalt.isp.support.OwnerSql;
import com.corebalt.isp.support.TestTenants;
import io.micrometer.core.instrument.MeterRegistry;
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

/**
 * The money path of docs/05 over real PostgreSQL and RabbitMQ: payments records, billing credits
 * and issues the receipt, subscriptions renews (P1, P4, P5, B1, B3, B4, B7).
 */
class BillingIT extends IntegrationTest {

    @Value("${local.server.port}")
    int port;

    @Autowired JdbcClient jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired OutboxRelay relay;
    @Autowired WalletService wallets;
    @Autowired BillingChecks checks;
    @Autowired MeterRegistry meters;

    TestTenants.Created tenant;
    Api api;
    String customerId;
    String lineId;

    @BeforeEach
    void customerWithAPendingLine() {
        tenant = TestTenants.createWithCode(jdbc, tx);
        api = new Api(port, tenant.id());
        final String packageId = Catalog.pppoePackage(api, "Home 10");
        final String routerId = Catalog.router(api, "Utawala-01").json().get("router").get("id").asString();
        customerId = Catalog.customer(api, "Wanjiku Kamau", "0712345678");
        lineId = api.post("/api/v1/subscriptions",
                        "{\"customerId\":\"" + customerId + "\",\"packageId\":\"" + packageId + "\",\"routerId\":\"" + routerId + "\"}")
                .json().get("subscription").get("id").asString();
    }

    @Test
    void P4_aManualPaymentFlowsThroughTheLedgerToARenewal() {
        final Api.Response recorded = pay("CASH-0001", 250_000);
        assertThat(recorded.status()).isEqualTo(201);
        assertThat(recorded.json().get("status").asString()).isEqualTo("MATCHED");

        // payments -> PaymentReceived -> billing credits and issues a receipt -> WalletCredited ->
        // subscriptions renews; each hop through the outbox and RabbitMQ.
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(300)).untilAsserted(() -> {
            TenantContext.callAsTenant(tenant.id(), relay::relayTenant);
            assertThat(api.get("/api/v1/subscriptions/" + lineId).json().get("state").asString()).isEqualTo("ACTIVE");
        });

        final JsonNode entries = api.get("/api/v1/customers/" + customerId + "/wallet/entries").json();
        assertThat(entries.get("total").asLong()).isEqualTo(2);
        assertThat(entries.get("items").get(0).get("type").asString()).isEqualTo("RENEWAL_CHARGE");
        assertThat(entries.get("items").get(0).get("amount").get("amount").asLong()).isEqualTo(-250_000);
        assertThat(entries.get("items").get(0).get("balanceAfter").get("amount").asLong()).isZero();
        assertThat(entries.get("items").get(1).get("type").asString()).isEqualTo("PAYMENT");
        assertThat(entries.get("items").get(1).get("balanceAfter").get("amount").asLong()).isEqualTo(250_000);

        final JsonNode receipts = api.get("/api/v1/receipts?customerId=" + customerId).json();
        assertThat(receipts.get("total").asLong()).isOne();
        assertThat(receipts.get("items").get(0).get("number").asString()).isEqualTo(tenant.code() + "-R000001");
        assertThat(TenantContext.callAsTenant(tenant.id(), checks::uncreditedPayments)).isEmpty();
    }

    @Test
    void B5_partialPaymentWaitsInTheWallet() {
        pay("CASH-0002", 100_000);
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(300)).untilAsserted(() -> {
            TenantContext.callAsTenant(tenant.id(), relay::relayTenant);
            assertThat(api.get("/api/v1/customers/" + customerId + "/wallet").json().get("balance").get("amount").asLong())
                    .isEqualTo(100_000);
        });
        TenantContext.callAsTenant(tenant.id(), relay::relayTenant);
        assertThat(api.get("/api/v1/subscriptions/" + lineId).json().get("state").asString()).isEqualTo("PENDING");
    }

    @Test
    void P5_manualPaymentsNeedThePermissionAKeyAndAUniqueReference() {
        final Api clerk = new Api(port, tenant.id());
        final Api withoutPermission = new Api(port, tenant.id(), "billing.adjust");
        assertThat(withoutPermission.postWithKey("/api/v1/payments/manual", "m1", body("CASH-1", 1000)).status())
                .isEqualTo(403);
        assertThat(clerk.post("/api/v1/payments/manual", body("CASH-1", 1000)).status()).isEqualTo(400);

        final Api.Response first = clerk.postWithKey("/api/v1/payments/manual", "m2", body("CASH-1", 1000));
        final Api.Response replay = clerk.postWithKey("/api/v1/payments/manual", "m2", body("CASH-1", 1000));
        assertThat(replay.header("Idempotent-Replayed")).isEqualTo("true");
        assertThat(replay.json().get("id")).isEqualTo(first.json().get("id"));

        final Api.Response duplicate = clerk.postWithKey("/api/v1/payments/manual", "m3", body("CASH-1", 1000));
        assertThat(duplicate.status()).isEqualTo(409);
        assertThat(duplicate.body()).contains("P1");

        assertThat(OwnerSql.client()
                        .sql("select count(*) from identity_audit_log where action = 'payments.manual_recorded' and tenant_id = :t")
                        .param("t", tenant.id())
                        .query(Long.class)
                        .single())
                .isOne();
    }

    @Test
    void P4_aRedeliveredPaymentIsCreditedOnceEvenWithoutTheConsumersRecord() {
        final PaymentReceived payment = new PaymentReceived(UuidV7.next(), tenant.id(), Instant.now(), UUID.randomUUID(),
                UUID.fromString(customerId), 50_000, "KES", "CASH", "R-1", Instant.now());
        credit(payment);
        credit(payment);
        assertThat(api.get("/api/v1/customers/" + customerId + "/wallet").json().get("balance").get("amount").asLong())
                .isEqualTo(50_000);
        assertThat(api.get("/api/v1/receipts?customerId=" + customerId).json().get("total").asLong()).isOne();
    }

    @Test
    void B1_theLedgerIsAppendOnlyForTheApplication() {
        credit(new PaymentReceived(UuidV7.next(), tenant.id(), Instant.now(), UUID.randomUUID(),
                UUID.fromString(customerId), 50_000, "KES", "CASH", "R-2", Instant.now()));
        TenantContext.runAsTenant(tenant.id(), () -> {
            assertThatThrownBy(() -> tx.execute(s -> jdbc.sql("update billing_ledger_entry set amount_minor = 1").update()))
                    .rootCause().hasMessageContaining("permission denied");
            assertThatThrownBy(() -> tx.execute(s -> jdbc.sql("delete from billing_ledger_entry").update()))
                    .rootCause().hasMessageContaining("permission denied");
        });
    }

    @Test
    void B1_adjustmentsNeedAReasonAndCannotTakeTheWalletBelowZero() {
        final String path = "/api/v1/customers/" + customerId + "/wallet/adjustments";
        assertThat(api.postWithKey(path, "a0", "{\"amount\":{\"amount\":1000,\"currency\":\"KES\"}}").status()).isEqualTo(400);
        final Api.Response credit =
                api.postWithKey(path, "a1", "{\"amount\":{\"amount\":1000,\"currency\":\"KES\"},\"reason\":\"Outage credit\"}");
        assertThat(credit.status()).isEqualTo(201);
        assertThat(credit.json().get("reason").asString()).isEqualTo("Outage credit");
        assertThat(api.postWithKey(path, "a2", "{\"amount\":{\"amount\":-2000,\"currency\":\"KES\"},\"reason\":\"Typo\"}").status())
                .isEqualTo(409);
        assertThat(new Api(port, tenant.id(), "payments.record_manual")
                        .postWithKey(path, "a3", "{\"amount\":{\"amount\":1000,\"currency\":\"KES\"},\"reason\":\"x\"}")
                        .status())
                .isEqualTo(403);
    }

    @Test
    void B4_aDriftedCachedBalanceIsReported() {
        credit(new PaymentReceived(UuidV7.next(), tenant.id(), Instant.now(), UUID.randomUUID(),
                UUID.fromString(customerId), 50_000, "KES", "CASH", "R-3", Instant.now()));
        assertThat(TenantContext.callAsTenant(tenant.id(), checks::walletDrift)).isEmpty();
        OwnerSql.client().sql("update billing_wallet set balance_minor = 49_999 where customer_id = cast(:c as uuid)")
                .param("c", customerId).update();
        assertThat(TenantContext.callAsTenant(tenant.id(), checks::walletDrift))
                .singleElement()
                .satisfies(d -> assertThat(d.cachedMinor()).isEqualTo(49_999));
    }

    @Test
    void P4_aMatchedPaymentWithoutALedgerEntryAfterFiveMinutesIsReported() {
        final UUID stuck = UUID.randomUUID();
        OwnerSql.client().sql("""
                insert into payments_payment (id, tenant_id, channel, provider_txn_id, amount_minor, received_at, status,
                                              customer_id, matched_at, raw_payload)
                values (:id, :t, 'CASH', 'STUCK-1', 1000, now() - interval '10 minutes', 'MATCHED',
                        cast(:c as uuid), now() - interval '10 minutes', '{}')
                """).param("id", stuck).param("t", tenant.id()).param("c", customerId).update();
        assertThat(TenantContext.callAsTenant(tenant.id(), checks::uncreditedPayments))
                .extracting(p -> p.paymentId())
                .containsExactly(stuck);
    }

    @Test
    void B4_P4_theScheduledChecksSeeTenantRowsAndSetTheirGauges() {
        // Baseline from a run: other tests' stuck payments share the database.
        checks.uncreditedEveryFewMinutes();
        final double uncreditedBefore = gauge("isp.billing.payments.uncredited");
        OwnerSql.client().sql("""
                insert into payments_payment (id, tenant_id, channel, provider_txn_id, amount_minor, received_at, status,
                                              customer_id, matched_at, raw_payload)
                values (:id, :t, 'CASH', 'STUCK-2', 1000, now() - interval '10 minutes', 'MATCHED',
                        cast(:c as uuid), now() - interval '10 minutes', '{}')
                """).param("id", UUID.randomUUID()).param("t", tenant.id()).param("c", customerId).update();
        checks.uncreditedEveryFewMinutes();
        assertThat(gauge("isp.billing.payments.uncredited")).isEqualTo(uncreditedBefore + 1);
        // An old payment that did reach the ledger is not counted.
        final UUID credited = UUID.randomUUID();
        OwnerSql.client().sql("""
                insert into payments_payment (id, tenant_id, channel, provider_txn_id, amount_minor, received_at, status,
                                              customer_id, matched_at, raw_payload)
                values (:id, :t, 'CASH', 'OLD-OK', 1000, now() - interval '10 minutes', 'MATCHED',
                        cast(:c as uuid), now() - interval '10 minutes', '{}')
                """).param("id", credited).param("t", tenant.id()).param("c", customerId).update();
        credit(new PaymentReceived(UuidV7.next(), tenant.id(), Instant.now(), credited,
                UUID.fromString(customerId), 1000, "KES", "CASH", "OLD-OK", Instant.now()));
        checks.uncreditedEveryFewMinutes();
        assertThat(gauge("isp.billing.payments.uncredited")).isEqualTo(uncreditedBefore + 1);

        credit(new PaymentReceived(UuidV7.next(), tenant.id(), Instant.now(), UUID.randomUUID(),
                UUID.fromString(customerId), 50_000, "KES", "CASH", "R-4", Instant.now()));
        checks.nightlyDrift();
        final double driftBefore = gauge("isp.billing.wallet.drift");
        OwnerSql.client().sql("update billing_wallet set balance_minor = 1 where customer_id = cast(:c as uuid)")
                .param("c", customerId).update();
        checks.nightlyDrift();
        assertThat(gauge("isp.billing.wallet.drift")).isEqualTo(driftBefore + 1);
    }

    private double gauge(final String name) {
        return meters.get(name).gauge().value();
    }

    private void credit(final PaymentReceived payment) {
        TenantContext.runAsTenant(tenant.id(), () -> tx.executeWithoutResult(s -> wallets.creditPayment(payment)));
    }

    private Api.Response pay(final String reference, final long amount) {
        return api.postWithKey("/api/v1/payments/manual", "pay-" + reference, body(reference, amount));
    }

    private String body(final String reference, final long amount) {
        return "{\"customerId\":\"" + customerId + "\",\"amount\":{\"amount\":" + amount + ",\"currency\":\"KES\"},"
                + "\"channel\":\"CASH\",\"reference\":\"" + reference + "\"}";
    }
}
