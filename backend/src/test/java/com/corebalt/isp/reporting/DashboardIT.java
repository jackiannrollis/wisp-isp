package com.corebalt.isp.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import com.corebalt.isp.billing.application.WalletService;
import com.corebalt.isp.shared.money.Money;
import com.corebalt.isp.shared.tenant.TenantContext;
import com.corebalt.isp.subscriptions.api.RenewalResultDto;
import com.corebalt.isp.subscriptions.application.RenewalService;
import com.corebalt.isp.support.Api;
import com.corebalt.isp.support.Catalog;
import com.corebalt.isp.support.IntegrationTest;
import com.corebalt.isp.support.OwnerSql;
import com.corebalt.isp.support.TestTenants;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * The admin dashboard (docs/09 step 8): lines by stored state and derived display status (S1),
 * renewals due and money collected in a tenant-local period, routers, open sessions and usage (N7).
 */
class DashboardIT extends IntegrationTest {

    private static final ZoneId NAIROBI = ZoneId.of("Africa/Nairobi");

    @Value("${local.server.port}")
    int port;

    @Autowired JdbcClient jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired RenewalService renewals;
    @Autowired WalletService wallets;

    UUID tenant;
    Api api;
    String packageId;
    String routerId;
    String tunnelIp;

    @BeforeEach
    void tenant() {
        tenant = TestTenants.create(jdbc, tx);
        OwnerSql.client().sql("update tenancy_tenant set time_zone = 'Africa/Nairobi' where id = :id").param("id", tenant).update();
        api = new Api(port, tenant);
        packageId = Catalog.pppoePackage(api, "Home 10");
        final JsonNode router = Catalog.router(api, "Thika-01").json().get("router");
        routerId = router.get("id").asString();
        tunnelIp = router.get("tunnelIp").asString();
    }

    @Test
    void S1_linesByStoredStateAndDerivedDisplayStatus() {
        line("Akinyi", null);                                         // PENDING
        line("Baraka", Instant.now().plus(Duration.ofDays(20)));      // ACTIVE
        line("Chebet", Instant.now().plus(Duration.ofDays(2)));       // EXPIRING (within 3 days)
        line("Daudi", Instant.now().minus(Duration.ofDays(10)));      // RESTRICTED (past grace)

        final JsonNode lines = api.get("/api/v1/dashboard").json().get("lines");
        assertThat(lines.get("total").asLong()).isEqualTo(4);
        assertThat(lines.get("byState").get("ACTIVE").asLong()).isEqualTo(3);
        assertThat(lines.get("byState").get("PENDING").asLong()).isEqualTo(1);
        assertThat(lines.get("byState").get("TERMINATED").asLong()).isZero();
        final JsonNode display = lines.get("byDisplayStatus");
        assertThat(display.get("PENDING").asLong()).isEqualTo(1);
        assertThat(display.get("ACTIVE").asLong()).isEqualTo(1);
        assertThat(display.get("EXPIRING").asLong()).isEqualTo(1);
        assertThat(display.get("RESTRICTED").asLong()).isEqualTo(1);
        assertThat(display.get("GRACE").asLong()).isZero();
        assertThat(lines.get("pendingOver14Days").asLong()).as("Akinyi's line is new").isZero();
    }

    @Test
    void S9_linesPendingForMoreThan14DaysAreCountedForTheQueue() {
        line("Akinyi", null);
        line("Baraka", null);
        OwnerSql.client().sql("""
                        update subscriptions_subscription set created_at = now() - interval '15 days'
                        where tenant_id = :t and pppoe_username = (
                            select min(pppoe_username) from subscriptions_subscription where tenant_id = :t)
                        """)
                .param("t", tenant).update();
        assertThat(api.get("/api/v1/dashboard").json().get("lines").get("pendingOver14Days").asLong()).isOne();
    }

    @Test
    void B5_renewalsDueOutstandingAndLapsed() {
        final LocalDate today = LocalDate.now(NAIROBI);
        final String esther = line("Esther", today.plusDays(5).atTime(12, 0).atZone(NAIROBI).toInstant());
        fundLineOwner(esther, 100_000);
        line("Faith", today.plusDays(9).atTime(23, 30).atZone(NAIROBI).toInstant());   // last minutes of the last day
        line("Gitau", today.plusDays(10).atStartOfDay(NAIROBI).toInstant());           // first instant after it
        line("Halima", today.minusDays(3).atTime(9, 0).atZone(NAIROBI).toInstant());   // before it

        final JsonNode revenue = api.get("/api/v1/dashboard?from=" + today + "&to=" + today.plusDays(9)).json().get("revenue");
        assertThat(revenue.get("renewalsDueLines").asLong()).isEqualTo(2);
        assertThat(revenue.get("renewalsDue").get("amount").asLong()).isEqualTo(500_000);
        assertThat(revenue.get("renewalsDue").get("currency").asString()).isEqualTo("KES");
        // Esther's wallet holds 1,000 of her 2,500; Faith's holds nothing.
        assertThat(revenue.get("outstanding").get("amount").asLong()).isEqualTo(150_000 + 250_000);
        // Halima expired before the period and has not renewed: the churn list.
        assertThat(revenue.get("lapsedLines").asLong()).isOne();
        assertThat(revenue.get("lapsed").get("amount").asLong()).isEqualTo(250_000);
    }

    @Test
    void A2_withoutFinanceReadTheDashboardLeavesOutEveryMoneyFigure() {
        line("Kamau", Instant.now().plus(Duration.ofDays(5)));
        final Api technician = new Api(port, tenant, "network.write", "subscriptions.write");
        final Api.Response response = technician.get("/api/v1/dashboard");
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.json().has("revenue")).isFalse();
        assertThat(response.json().has("suspense")).isFalse();
        assertThat(response.body()).doesNotContain("\"amount\"");
        assertThat(response.json().get("lines").get("total").asLong()).isOne();
        assertThat(response.json().get("network")).isNotNull();

        final String customer = OwnerSql.client().sql("select customer_id::text from subscriptions_subscription where tenant_id = :t")
                .param("t", tenant).query(String.class).single();
        assertThat(technician.get("/api/v1/customers/" + customer + "/wallet").status()).isEqualTo(403);
        assertThat(technician.get("/api/v1/customers/" + customer + "/wallet/entries").status()).isEqualTo(403);
        assertThat(technician.get("/api/v1/receipts").status()).isEqualTo(403);
        assertThat(technician.get("/api/v1/payments").status()).isEqualTo(403);
        assertThat(technician.get("/api/v1/payments/suspense").status()).isEqualTo(403);
        assertThat(new Api(port, tenant, "finance.read").get("/api/v1/dashboard").json().has("revenue")).isTrue();
    }

    private void fundLineOwner(final String username, final long minor) {
        final UUID customer = OwnerSql.client().sql("select customer_id from subscriptions_subscription where pppoe_username = :u")
                .param("u", username).query(UUID.class).single();
        TenantContext.runAsTenant(tenant, () -> tx.executeWithoutResult(
                s -> wallets.adjust(customer, Money.ofMinor(minor, "KES"), "test funding")));
    }

    @Test
    void B2_P3_collectedSumsPaymentsReceivedInTheTenantLocalPeriod() {
        final LocalDate day = LocalDate.now(NAIROBI).minusDays(2);
        final Instant start = day.atStartOfDay(NAIROBI).toInstant();
        final UUID customer = UUID.fromString(Catalog.customer(api, "Imani", "0733000555"));
        payment("in-1", customer, "MATCHED", 120_000, start);                                  // first instant of the day
        payment("in-2", customer, "MATCHED", 80_000, start.plus(Duration.ofHours(23)));
        payment("in-3", null, "UNMATCHED", 30_000, start.plus(Duration.ofHours(5)));
        payment("reversed", customer, "REVERSED", 999_900, start.plus(Duration.ofHours(6)));
        payment("before", customer, "MATCHED", 10_000, start.minusSeconds(1));                // 23:59:59 the day before
        payment("after", customer, "MATCHED", 20_000, start.plus(Duration.ofDays(1)));

        final JsonNode revenue = api.get("/api/v1/dashboard?from=" + day + "&to=" + day).json().get("revenue");
        assertThat(revenue.get("payments").asLong()).isEqualTo(3);
        assertThat(revenue.get("collected").get("amount").asLong()).isEqualTo(230_000);
        assertThat(revenue.get("unmatchedPayments").asLong()).isOne();
        assertThat(revenue.get("unmatched").get("amount").asLong()).isEqualTo(30_000);

        // Per tenant-local day, zero days included, adding up to the period's total.
        final JsonNode threeDays = api.get("/api/v1/dashboard?from=" + day.minusDays(1) + "&to=" + day.plusDays(1)).json();
        final JsonNode byDay = threeDays.get("revenue").get("collectedByDay");
        assertThat(byDay).extracting(d -> d.get("day").asString())
                .containsExactly(day.minusDays(1).toString(), day.toString(), day.plusDays(1).toString());
        assertThat(byDay).extracting(d -> d.get("amount").get("amount").asLong()).containsExactly(10_000L, 230_000L, 20_000L);
        assertThat(byDay).extracting(d -> d.get("payments").asLong()).containsExactly(1L, 3L, 1L);
        final JsonNode quiet = api.get("/api/v1/dashboard?from=" + day.minusDays(10) + "&to=" + day.minusDays(10)).json();
        assertThat(quiet.get("revenue").get("collectedByDay").get(0).get("amount").get("amount").asLong()).isZero();

        // The suspense queue as it stands now, whatever the period (P3).
        assertThat(quiet.get("suspense").get("payments").asLong()).isOne();
        assertThat(quiet.get("suspense").get("amount").get("amount").asLong()).isEqualTo(30_000);
        final JsonNode assigner = new Api(port, tenant, "payments.assign").get("/api/v1/dashboard").json();
        assertThat(assigner.has("revenue")).isFalse();
        assertThat(assigner.get("suspense").get("payments").asLong()).as("leads the assigner to the queue").isOne();
    }

    @Test
    void N7_routersOpenSessionsAndUsageFromTheDailyTable() {
        OwnerSql.client().sql("update network_router set status = 'ONLINE' where id = :id")
                .param("id", UUID.fromString(routerId)).update();
        Catalog.router(api, "Thika-02");
        final String username = line("Jabali", Instant.now().plus(Duration.ofDays(20)));
        OwnerSql.client().sql("""
                        insert into radacct (acctsessionid, acctuniqueid, username, nasipaddress, acctstarttime)
                        values ('d1', :uid, :u, cast(:nas as inet), now() - interval '5 minutes')
                        """)
                .param("uid", "dash-" + tenant).param("u", username).param("nas", tunnelIp).update();
        final UUID lineId = OwnerSql.client().sql("select id from subscriptions_subscription where pppoe_username = :u")
                .param("u", username).query(UUID.class).single();
        final LocalDate today = LocalDate.now(NAIROBI);
        OwnerSql.client().sql("""
                        insert into network_usage_daily (tenant_id, subscription_id, day, input_octets, output_octets, updated_at)
                        values (:t, :s, :d1, 100, 1000, now()), (:t, :s, :d2, 200, 2000, now()), (:t, :s, :old, 7, 7, now())
                        """)
                .param("t", tenant).param("s", lineId).param("d1", today.minusDays(1)).param("d2", today)
                .param("old", today.minusDays(40))
                .update();

        final JsonNode network = api.get("/api/v1/dashboard?from=" + today.minusDays(6) + "&to=" + today).json().get("network");
        assertThat(network.get("routersByStatus").get("ONLINE").asLong()).isOne();
        assertThat(network.get("routersByStatus").get("NOT_MONITORED").asLong()).isOne();
        assertThat(network.get("activeSessions").asLong()).isOne();
        assertThat(network.get("inputOctets").asLong()).isEqualTo(300);
        assertThat(network.get("outputOctets").asLong()).isEqualTo(3000);
    }

    @Test
    void theDefaultPeriodIsTheCurrentTenantLocalMonth_andAPeriodIsBounded() {
        final JsonNode dashboard = api.get("/api/v1/dashboard").json();
        final LocalDate today = LocalDate.now(NAIROBI);
        assertThat(dashboard.get("from").asString()).isEqualTo(today.withDayOfMonth(1).toString());
        assertThat(dashboard.get("to").asString()).isEqualTo(today.withDayOfMonth(today.lengthOfMonth()).toString());
        assertThat(dashboard.get("zone").asString()).isEqualTo("Africa/Nairobi");
        assertThat(api.get("/api/v1/dashboard?from=2026-01-01&to=2027-06-01").status()).isEqualTo(400);
        assertThat(api.get("/api/v1/dashboard?from=2026-02-01&to=2026-01-01").status()).isEqualTo(400);
    }

    /** A line, activated and then given {@code expiresAt} with no grace; null leaves it PENDING. Returns its username. */
    private String line(final String name, final Instant expiresAt) {
        final UUID customer = UUID.fromString(Catalog.customer(api, name + " Test", "07" + (10_000_000 + Math.abs(name.hashCode() % 89_999_999))));
        final JsonNode opened = api.post("/api/v1/subscriptions", "{\"customerId\":\"" + customer + "\",\"packageId\":\""
                + packageId + "\",\"routerId\":\"" + routerId + "\"}").json().get("subscription");
        final UUID lineId = UUID.fromString(opened.get("id").asString());
        if (expiresAt != null) {
            TenantContext.runAsTenant(tenant, () -> tx.executeWithoutResult(
                    s -> wallets.adjust(customer, Money.ofMinor(250_000, "KES"), "test funding")));
            assertThat(TenantContext.callAsTenant(tenant, () -> renewals.renew(lineId)).outcome())
                    .isEqualTo(RenewalResultDto.Outcome.RENEWED);
            OwnerSql.client().sql("update subscriptions_subscription set expires_at = :e, grace_days = 0 where id = :id")
                    .param("e", Timestamp.from(expiresAt)).param("id", lineId).update();
        }
        return opened.get("pppoeUsername").asString();
    }

    private void payment(final String ref, final UUID customer, final String status, final long minor, final Instant at) {
        OwnerSql.client().sql("""
                        insert into payments_payment (id, tenant_id, channel, provider_txn_id, amount_minor, received_at, status,
                                                      customer_id, matched_at, raw_payload)
                        values (:id, :t, 'MPESA_PAYBILL', :ref, :amount, :at, :status, :customer,
                                case when :status = 'MATCHED' then cast(:at as timestamptz) end, '{}')
                        """)
                .param("id", UUID.randomUUID()).param("t", tenant).param("ref", ref).param("amount", minor)
                .param("at", Timestamp.from(at)).param("status", status).param("customer", customer)
                .update();
    }
}
