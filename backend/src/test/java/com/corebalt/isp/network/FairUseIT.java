package com.corebalt.isp.network;

import static org.assertj.core.api.Assertions.assertThat;

import com.corebalt.isp.billing.application.WalletService;
import com.corebalt.isp.network.api.UsageThresholdReached;
import com.corebalt.isp.network.application.AccessChanges;
import com.corebalt.isp.network.application.Accounting;
import com.corebalt.isp.notifications.application.MessageComposer;
import com.corebalt.isp.shared.money.Money;
import com.corebalt.isp.shared.tenant.TenantContext;
import com.corebalt.isp.subscriptions.api.RenewalResultDto;
import com.corebalt.isp.subscriptions.api.SubscriptionRenewed;
import com.corebalt.isp.subscriptions.application.RenewalService;
import com.corebalt.isp.support.Api;
import com.corebalt.isp.support.Catalog;
import com.corebalt.isp.support.IntegrationTest;
import com.corebalt.isp.support.OwnerSql;
import com.corebalt.isp.support.TestTenants;
import com.corebalt.isp.support.TestTokens;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Fair use and data caps end to end (docs/09 step 8b): accounting growth reaches the line's cycle total
 * through the usage roll-up (N7); past the allowance the RADIUS view throttles the line and AccessPolicy
 * agrees (S3); the crossing is announced once (UsageThresholdReached), disconnects the live session
 * (N5) and queues one SMS per cycle (M1, Q15); a renewal starts a new cycle that lifts the throttle the
 * moment it commits and disconnects the session back to full speed (Q12, Q13).
 */
class FairUseIT extends IntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final long GB = 1_000_000_000L;

    @Value("${local.server.port}")
    int port;

    @Autowired JdbcClient jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired Accounting accounting;
    @Autowired AccessChanges changes;
    @Autowired RenewalService renewals;
    @Autowired WalletService wallets;
    @Autowired MessageComposer composer;

    UUID tenant;
    Api api;
    UUID customerId;
    UUID lineId;
    String username;
    String tunnelIp;

    @BeforeEach
    void renewedLineOnAFairUsePackage() {
        tenant = TestTenants.create(jdbc, tx);
        api = new Api(port, tenant);
        final String packageId = api.post("/api/v1/packages", """
                {"name":"Home Fair 10","downloadKbps":10000,"uploadKbps":5000,"durationMinutes":43200,
                 "price":{"amount":250000,"currency":"KES"},"throttleDownloadKbps":2000,"throttleUploadKbps":1000,
                 "fupBytes":%d,"dataCapBytes":%d}""".formatted(2 * GB, 5 * GB)).json().get("id").asString();
        final JsonNode router = Catalog.router(api, "Thika-01").json().get("router");
        tunnelIp = router.get("tunnelIp").asString();
        customerId = UUID.fromString(Catalog.customer(api, "Wambui Njoroge", "0722000333"));
        final JsonNode opened = api.post("/api/v1/subscriptions", "{\"customerId\":\"" + customerId + "\",\"packageId\":\""
                + packageId + "\",\"routerId\":\"" + router.get("id").asString() + "\"}").json();
        lineId = UUID.fromString(opened.get("subscription").get("id").asString());
        username = opened.get("subscription").get("pppoeUsername").asString();
        fund(250_000);
        assertThat(TenantContext.callAsTenant(tenant, () -> renewals.renew(lineId)).outcome())
                .isEqualTo(RenewalResultDto.Outcome.RENEWED);
    }

    @Test
    void S3_Q12_Q13_pastTheAllowanceTheLineIsThrottledUntilARenewalStartsANewCycle() {
        final String session = session(GB, GB + 1); // 2 GB and one byte, download plus upload
        assertThat(rollUp()).isOne();

        assertThat(counter()).containsEntry("octets", 2 * GB + 1);
        assertThat(thresholds()).extracting(e -> e.get("kind").asString()).containsExactly("FAIR_USE");
        assertThat(viewAttribute("Mikrotik-Address-List")).isEqualTo("throttled");
        assertThat(viewAttribute("Mikrotik-Rate-Limit")).isEqualTo("1M/2M");
        final JsonNode line = api.get("/api/v1/subscriptions/" + lineId).json();
        assertThat(line.get("accessProfile").asString()).as("AccessPolicy agrees with the view (S3)").isEqualTo("THROTTLED");
        assertThat(line.get("usage").get("usedBytes").asLong()).isEqualTo(2 * GB + 1);
        assertThat(line.get("usage").get("fairUseBytes").asLong()).isEqualTo(2 * GB);
        assertThat(line.get("usage").get("fairUseExceeded").asBoolean()).isTrue();
        assertThat(line.get("usage").get("dataCapReached").asBoolean()).isFalse();
        assertThat(line.get("displayStatus").asString()).as("never ACTIVE while throttled").isEqualTo("THROTTLED");
        assertThat(line.get("accessReason").asString()).isEqualTo("FAIR_USE");

        // The portal offers early renewal, not a due payment, and pay-all includes it.
        OwnerSql.client().sql("insert into identity_customer_credential (tenant_id, customer_id) values (:t, :c)")
                .param("t", tenant).param("c", customerId).update();
        final JsonNode portal = new Api(port, TestTokens.customer(customerId, tenant)).get("/api/v1/portal/lines").json();
        final JsonNode mine = portal.get("lines").get(0);
        assertThat(mine.get("due").asBoolean()).isFalse();
        assertThat(mine.get("earlyRenewal").asBoolean()).isTrue();
        assertThat(mine.get("accessReason").asString()).isEqualTo("FAIR_USE");
        assertThat(mine.get("payToRenew").get("amount").asLong()).isEqualTo(250_000);
        assertThat(portal.get("payAll").get("amount").asLong()).isEqualTo(250_000);

        // More traffic in the same cycle announces nothing new.
        grow(session, GB, GB + 500);
        rollUp();
        assertThat(thresholds()).hasSize(1);

        // The crossing disconnects the live session so it redials throttled (N5).
        final UsageThresholdReached reached = JSON.readValue(thresholds().getFirst().toString(), UsageThresholdReached.class);
        assertThat(run(() -> changes.usageThresholdReached(reached))).isOne();
        assertThat(disconnects()).hasSize(1);

        // Paying renews early (S5) and starts a new cycle: full speed at the next login, at once (E4).
        fund(250_000);
        final RenewalResultDto renewed = TenantContext.callAsTenant(tenant, () -> renewals.renew(lineId));
        assertThat(renewed.previousAccessProfile()).isEqualTo("THROTTLED");
        assertThat(renewed.subscription().accessProfile()).isEqualTo("FULL");
        assertThat(renewed.subscription().usage().usedBytes()).as("the old cycle no longer counts").isZero();
        assertThat(viewAttribute("Mikrotik-Address-List")).isNull();
        assertThat(viewAttribute("Mikrotik-Rate-Limit")).isEqualTo("5M/10M");

        // The renewal itself carries the profile it lifted, so the consumer disconnects back to FULL.
        final SubscriptionRenewed event = JSON.readValue(latest("SubscriptionRenewed").toString(), SubscriptionRenewed.class);
        assertThat(event.previousAccessProfile()).isEqualTo("THROTTLED");
        assertThat(run(() -> changes.renewed(event))).isOne();
        assertThat(disconnects()).hasSize(2);

        // The next roll-up restarts the total for the new cycle: 100 more each way since the last one.
        grow(session, GB + 100, GB + 600);
        rollUp();
        assertThat(counter()).containsEntry("octets", 200L);
    }

    @Test
    void N5_aLineAlreadyThrottledByGraceIsNotDisconnectedWhenItPassesFairUse() {
        OwnerSql.client().sql("update tenancy_tenant set grace_mode = 'THROTTLED' where id = :id").param("id", tenant).update();
        OwnerSql.client().sql("update subscriptions_subscription set expires_at = now() - interval '1 hour', grace_days = 3 where id = :id")
                .param("id", lineId).update();
        session(GB, GB + 1);
        rollUp();
        final UsageThresholdReached reached = JSON.readValue(thresholds().getFirst().toString(), UsageThresholdReached.class);
        assertThat(run(() -> changes.usageThresholdReached(reached))).as("THROTTLED before and after").isZero();
        final JsonNode line = api.get("/api/v1/subscriptions/" + lineId).json();
        assertThat(line.get("displayStatus").asString()).as("expired in grace: simply due").isEqualTo("GRACE");
        assertThat(line.get("accessReason").asString()).isEqualTo("FAIR_USE");
    }

    @Test
    void Q14_atTheDataCapTheLineIsRestrictedUntilItRenews() {
        session(3 * GB, 2 * GB);
        rollUp();
        assertThat(thresholds()).extracting(e -> e.get("kind").asString()).containsExactlyInAnyOrder("FAIR_USE", "DATA_CAP");
        assertThat(viewAttribute("Mikrotik-Address-List")).isEqualTo("restricted");
        assertThat(viewAttribute("Framed-Pool")).isEqualTo("restricted");
        final JsonNode capped = api.get("/api/v1/subscriptions/" + lineId).json();
        assertThat(capped.get("accessProfile").asString()).isEqualTo("RESTRICTED");
        assertThat(capped.get("displayStatus").asString()).isEqualTo("CAPPED");
        assertThat(capped.get("accessReason").asString()).isEqualTo("DATA_CAP");
        assertThat(api.get("/api/v1/subscriptions?displayStatus=CAPPED").json().get("total").asLong()).isOne();
    }

    @Test
    void Q12_aLineWhoseCycleStartIsUnknownIsNotMetered() {
        OwnerSql.client().sql("update subscriptions_subscription set cycle_started_at = null where id = :id").param("id", lineId).update();
        session(3 * GB, 3 * GB);
        rollUp();
        assertThat(thresholds()).isEmpty();
        assertThat(viewAttribute("Mikrotik-Address-List")).isNull();
        assertThat(api.get("/api/v1/subscriptions/" + lineId).json().get("usage").get("usedBytes").asLong()).isZero();
    }

    @Test
    void M1_Q15_oneSmsPerLineAndCycle() {
        session(GB, GB);
        rollUp();
        final UsageThresholdReached reached = JSON.readValue(thresholds().getFirst().toString(), UsageThresholdReached.class);
        TenantContext.runAsTenant(tenant, () -> composer.usageThresholdReached(reached));
        TenantContext.runAsTenant(tenant, () -> composer.usageThresholdReached(reached));

        final List<Map<String, Object>> sms = OwnerSql.client().sql("""
                        select template_key, body from notifications_notification where tenant_id = :t and customer_id = :c
                        and template_key = 'FAIR_USE_REACHED'
                        """)
                .param("t", tenant).param("c", customerId).query().listOfRows();
        assertThat(sms).hasSize(1);
        assertThat((String) sms.getFirst().get("body"))
                .startsWith("Hi Wambui, you have used your 2 GB fair-use data on Home Fair 10, so speed is reduced.")
                .contains("Pay KES 2,500");
    }

    private void fund(final long minor) {
        TenantContext.runAsTenant(tenant, () -> tx.executeWithoutResult(
                s -> wallets.adjust(customerId, Money.ofMinor(minor, "KES"), "test funding")));
    }

    private String session(final long input, final long output) {
        final String id = UUID.randomUUID().toString();
        OwnerSql.client().sql("""
                        insert into radacct (acctsessionid, acctuniqueid, username, nasipaddress, acctstarttime, acctupdatetime,
                                             acctinputoctets, acctoutputoctets)
                        values (:id, :id, :username, cast(:nas as inet), :start, now(), :input, :output)
                        """)
                .param("id", id).param("username", username).param("nas", tunnelIp)
                .param("start", Timestamp.from(Instant.now().minusSeconds(600)))
                .param("input", input).param("output", output)
                .update();
        return id;
    }

    private void grow(final String session, final long input, final long output) {
        OwnerSql.client().sql("update radacct set acctinputoctets = :i, acctoutputoctets = :o, acctupdatetime = now() where acctuniqueid = :id")
                .param("i", input).param("o", output).param("id", session).update();
    }

    private int rollUp() {
        return TenantContext.callAsTenant(tenant, accounting::rollUpUsage);
    }

    private int run(final Supplier<Integer> work) {
        return TenantContext.callAsTenant(tenant, () -> tx.execute(s -> work.get()));
    }

    private Map<String, Object> counter() {
        return OwnerSql.client().sql("select octets from network_line_usage where subscription_id = :id")
                .param("id", lineId).query().singleRow();
    }

    private String viewAttribute(final String attribute) {
        return OwnerSql.client().sql("""
                        select value from v_radius_auth_v6 where username = :u and nasname = :nas and attribute = :a
                        """)
                .param("u", username).param("nas", tunnelIp).param("a", attribute)
                .query(String.class).optional().orElse(null);
    }

    private List<JsonNode> thresholds() {
        return events("UsageThresholdReached");
    }

    private List<JsonNode> disconnects() {
        return events("NetworkAccessChanged").stream().filter(e -> "DISCONNECT".equals(e.get("action").asString())).toList();
    }

    private JsonNode latest(final String type) {
        return events(type).getLast();
    }

    private List<JsonNode> events(final String type) {
        return OwnerSql.client().sql("select payload::text from outbox_event where tenant_id = :t and event_type = :type order by id")
                .param("t", tenant).param("type", type)
                .query(String.class).list().stream().map(JSON::readTree).toList();
    }
}
