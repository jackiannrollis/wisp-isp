package com.corebalt.isp.subscriptions;

import static org.assertj.core.api.Assertions.assertThat;

import com.corebalt.isp.billing.application.WalletService;
import com.corebalt.isp.shared.money.Money;
import com.corebalt.isp.shared.tenant.TenantContext;
import com.corebalt.isp.subscriptions.api.RenewalResultDto;
import com.corebalt.isp.subscriptions.application.ReminderService;
import com.corebalt.isp.subscriptions.application.RenewalService;
import com.corebalt.isp.subscriptions.application.RenewalTriggers;
import com.corebalt.isp.support.Api;
import com.corebalt.isp.support.Catalog;
import com.corebalt.isp.support.IntegrationTest;
import com.corebalt.isp.support.OwnerSql;
import com.corebalt.isp.support.TestTenants;
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

/** Renewal triggers, partial payments and package changes against the real wallet (docs/05, S10, B6). */
class RenewalIT extends IntegrationTest {

    @Value("${local.server.port}")
    int port;

    @Autowired JdbcClient jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired WalletService wallets;
    @Autowired RenewalService renewals;
    @Autowired RenewalTriggers triggers;
    @Autowired ReminderService reminders;

    UUID tenant;
    Api api;
    String home10;
    String routerId;
    UUID customerId;
    UUID lineId;

    @BeforeEach
    void line() {
        tenant = TestTenants.create(jdbc, tx);
        api = new Api(port, tenant);
        home10 = Catalog.pppoePackage(api, "Home 10");
        routerId = Catalog.router(api, "Utawala-01").json().get("router").get("id").asString();
        customerId = UUID.fromString(Catalog.customer(api, "Wanjiku Kamau", "0712345678"));
        lineId = openLine(home10);
    }

    @Test
    void anEarlyPaymentExtendsFromExpiryAndRenewsEachLineOnce() {
        fund(250_000);
        renew(lineId);
        final Instant firstExpiry = expiresAt(lineId);
        fund(500_000);
        assertThat(TenantContext.callAsTenant(tenant, () -> triggers.renewOnCredit(
                new RenewalTriggers.Credit(customerId, "ADJUSTMENT", UUID.randomUUID(), 500_000, "KES")))).isOne();
        assertThat(expiresAt(lineId)).isEqualTo(firstExpiry.plus(Duration.ofDays(30)));
        assertThat(balance()).isEqualTo(250_000);
    }

    @Test
    void dueRenewalUsesStoredCreditInsideTheWindowOnly() {
        fund(500_000);
        renew(lineId);
        // 30-day package: window min(24 h, 7.5 days) = 24 h.
        setExpiry(lineId, "25 hours");
        assertThat(TenantContext.callAsTenant(tenant, triggers::renewDue)).isZero();
        setExpiry(lineId, "23 hours");
        final Instant before = expiresAt(lineId);
        assertThat(TenantContext.callAsTenant(tenant, triggers::renewDue)).isOne();
        assertThat(expiresAt(lineId)).isEqualTo(before.plus(Duration.ofDays(30)));
        // Already expired (a job outage longer than the window) is caught too, from now.
        fund(250_000);
        setExpiry(lineId, "-3 days");
        assertThat(TenantContext.callAsTenant(tenant, triggers::renewDue)).isOne();
        assertThat(expiresAt(lineId)).isAfter(Instant.now().plus(Duration.ofDays(29)));
    }

    @Test
    void dayPackagesRenewWithinAQuarterOfTheirDuration() {
        final String daily = id(api.post("/api/v1/packages", """
                {"name":"Daily","downloadKbps":5000,"uploadKbps":2000,"durationMinutes":1440,
                 "price":{"amount":5000,"currency":"KES"}}
                """));
        final UUID dailyLine = openLine(daily);
        fund(20_000);
        renew(dailyLine);
        setExpiry(dailyLine, "7 hours");
        TenantContext.runAsTenant(tenant, () -> triggers.renewDue());
        assertThat(expiresAt(dailyLine)).isBefore(Instant.now().plus(Duration.ofHours(8)));
        setExpiry(dailyLine, "5 hours");
        TenantContext.runAsTenant(tenant, () -> triggers.renewDue());
        assertThat(expiresAt(dailyLine)).isAfter(Instant.now().plus(Duration.ofHours(28)));
    }

    @Test
    void proRataDaysWhenTheTenantChoseThem() {
        OwnerSql.client().sql("update tenancy_tenant set partial_payment_rule = 'PRO_RATA_DAYS' where id = :id")
                .param("id", tenant).update();
        fund(90_000);
        final RenewalResultDto result = renew(lineId);
        assertThat(result.outcome()).isEqualTo(RenewalResultDto.Outcome.RENEWED);
        assertThat(Duration.between(result.subscription().activatedAt(), result.subscription().expiresAt()))
                .isEqualTo(Duration.ofDays(10));
        assertThat(balance()).isEqualTo(90_000 - 83_400);
    }

    @Test
    void S10_B6_upgradeNowChargesProrationAndKeepsTheExpiry() {
        fund(250_000);
        renew(lineId);
        // 15 of 30 days left: half the price difference of KES 1,000, rounded up to the shilling.
        setExpiry(lineId, "15 days");
        final Instant expiry = expiresAt(lineId);
        final String home20 = id(api.post("/api/v1/packages", """
                {"name":"Home 20","downloadKbps":20000,"uploadKbps":10000,"durationMinutes":43200,
                 "price":{"amount":350000,"currency":"KES"}}
                """));
        assertThat(change(home20, "NOW").status()).isEqualTo(409); // the wallet is empty
        fund(60_000);
        final JsonNode changed = change(home20, "NOW").json();
        assertThat(changed.get("packageId").asString()).isEqualTo(home20);
        assertThat(expiresAt(lineId)).isEqualTo(expiry);
        final long charged = 60_000 - balance();
        assertThat(charged).isBetween(50_000L, 50_100L).matches(c -> c % 100 == 0, "whole shillings");
    }

    @Test
    void S10_aDowngradeWaitsForTheNextRenewalAndIsSoldThere() {
        fund(250_000);
        renew(lineId);
        final String basic = id(api.post("/api/v1/packages", """
                {"name":"Basic 5","downloadKbps":5000,"uploadKbps":2000,"durationMinutes":43200,
                 "price":{"amount":150000,"currency":"KES"}}
                """));
        assertThat(change(basic, "NOW").status()).isEqualTo(409);
        change(basic, "NEXT_RENEWAL");
        fund(150_000);
        final RenewalResultDto renewed = renew(lineId);
        assertThat(renewed.subscription().packageId()).isEqualTo(UUID.fromString(basic));
        assertThat(balance()).isZero();
    }

    @Test
    void remindersSayWhenTheWalletWillRenewTheLine() {
        fund(500_000);
        renew(lineId);
        setExpiry(lineId, "2 hours");
        TenantContext.runAsTenant(tenant, () -> reminders.raiseDue());
        final Boolean renewsAutomatically = OwnerSql.client()
                .sql("select (payload ->> 'renewsAutomatically')::boolean from outbox_event where event_type = 'SubscriptionReminderDue' and payload ->> 'subscriptionId' = :id")
                .param("id", lineId.toString())
                .query(Boolean.class)
                .single();
        assertThat(renewsAutomatically).isTrue();
    }

    private UUID openLine(final String packageId) {
        return UUID.fromString(api.post("/api/v1/subscriptions",
                        "{\"customerId\":\"" + customerId + "\",\"packageId\":\"" + packageId + "\",\"routerId\":\"" + routerId + "\"}")
                .json().get("subscription").get("id").asString());
    }

    private Api.Response change(final String packageId, final String timing) {
        return api.postWithKey("/api/v1/subscriptions/" + lineId + "/package", UUID.randomUUID().toString(),
                "{\"packageId\":\"" + packageId + "\",\"timing\":\"" + timing + "\"}");
    }

    private RenewalResultDto renew(final UUID line) {
        return TenantContext.callAsTenant(tenant, () -> renewals.renew(line));
    }

    private void fund(final long minorUnits) {
        TenantContext.runAsTenant(tenant, () -> tx.executeWithoutResult(
                s -> wallets.adjust(customerId, Money.ofMinor(minorUnits, "KES"), "test funding")));
    }

    private long balance() {
        return TenantContext.callAsTenant(tenant, () -> wallets.balance(customerId, Money.KES).minorUnits());
    }

    private static void setExpiry(final UUID line, final String fromNow) {
        OwnerSql.client()
                .sql("update subscriptions_subscription set expires_at = now() + cast(:i as interval) where id = :id")
                .param("i", fromNow)
                .param("id", line)
                .update();
    }

    private static Instant expiresAt(final UUID line) {
        return OwnerSql.client()
                .sql("select expires_at from subscriptions_subscription where id = :id")
                .param("id", line)
                .query(java.sql.Timestamp.class)
                .single()
                .toInstant();
    }

    private static String id(final Api.Response response) {
        return response.json().get("id").asString();
    }
}
