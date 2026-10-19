package com.corebalt.isp.network;

import static org.assertj.core.api.Assertions.assertThat;

import com.corebalt.isp.billing.application.WalletService;
import com.corebalt.isp.network.application.AccessChanges;
import com.corebalt.isp.shared.id.UuidV7;
import com.corebalt.isp.shared.money.Money;
import com.corebalt.isp.shared.tenant.TenantContext;
import com.corebalt.isp.subscriptions.api.RenewalResultDto;
import com.corebalt.isp.subscriptions.api.SubscriptionPackageChanged;
import com.corebalt.isp.subscriptions.api.SubscriptionRenewed;
import com.corebalt.isp.subscriptions.api.SubscriptionStateChanged;
import com.corebalt.isp.subscriptions.api.SubscriptionStateChanged.Cause;
import com.corebalt.isp.subscriptions.application.RenewalService;
import com.corebalt.isp.support.Api;
import com.corebalt.isp.support.Catalog;
import com.corebalt.isp.support.IntegrationTest;
import com.corebalt.isp.support.OwnerSql;
import com.corebalt.isp.support.TestTenants;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
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
 * The API's half of CoA and Disconnect (N5): which subscription changes become NetworkAccessChanged,
 * for which sessions, with which rate. The rate a CoA sets is compared with what v_radius_auth_v5
 * gives the same line at login (N4).
 */
class AccessChangesIT extends IntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Value("${local.server.port}")
    int port;

    @Autowired JdbcClient jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired AccessChanges changes;
    @Autowired RenewalService renewals;
    @Autowired WalletService wallets;
    @Autowired Clock clock;

    UUID tenant;
    Api api;
    UUID customerId;
    UUID lineId;
    String username;
    String tunnelIp;
    String packageId;

    @BeforeEach
    void activeLine() {
        tenant = TestTenants.create(jdbc, tx);
        api = new Api(port, tenant);
        packageId = Catalog.pppoePackage(api, "Home 10");
        final JsonNode router = Catalog.router(api, "Kitengela-01").json().get("router");
        tunnelIp = router.get("tunnelIp").asString();
        customerId = UUID.fromString(Catalog.customer(api, "Achieng Otieno", "0722000111"));
        final JsonNode opened = api.post("/api/v1/subscriptions", "{\"customerId\":\"" + customerId + "\",\"packageId\":\""
                + packageId + "\",\"routerId\":\"" + router.get("id").asString() + "\"}").json();
        lineId = UUID.fromString(opened.get("subscription").get("id").asString());
        username = opened.get("subscription").get("pppoeUsername").asString();
        TenantContext.runAsTenant(tenant, () -> tx.executeWithoutResult(
                s -> wallets.adjust(customerId, Money.ofMinor(250_000, "KES"), "test funding")));
        assertThat(TenantContext.callAsTenant(tenant, () -> renewals.renew(lineId)).outcome())
                .isEqualTo(RenewalResultDto.Outcome.RENEWED);
    }

    @Test
    void N5_aStateChangeDisconnectsEveryOpenSessionOfTheLine() {
        openSession("81a00001", Duration.ofMinutes(30));
        openSession("81a00002", Duration.ofMinutes(5));
        closedSession("81a00000");

        assertThat(run(() -> changes.stateChanged(stateChanged(Cause.STAFF_ACTION)))).isEqualTo(2);

        assertThat(requests()).extracting(r -> r.get("acctSessionId").asString() + ":" + r.get("action").asString()
                        + ":" + r.get("username").asString())
                .containsExactlyInAnyOrder("81a00001:DISCONNECT:" + username, "81a00002:DISCONNECT:" + username);
        assertThat(requests()).allSatisfy(r -> assertThat(r.get("rateLimit").isNull()).isTrue());
    }

    @Test
    void S7_anAutoResumeTheViewAlreadyAppliedSendsNothing() {
        openSession("81a00003", Duration.ofMinutes(1));
        assertThat(run(() -> changes.stateChanged(stateChanged(Cause.AUTO_RESUME_RECORDED)))).isZero();
        assertThat(requests()).isEmpty();
    }

    @Test
    void N5_aLineWithNoOpenSessionGetsNothing() {
        closedSession("81a00004");
        openSessionStartedAt("81a00005", clock.instant().minus(Duration.ofDays(3))); // older than any live session (S4)
        assertThat(run(() -> changes.stateChanged(stateChanged(Cause.STAFF_ACTION)))).isZero();
    }

    @Test
    void N5_aRenewalThatLiftsRestrictionDisconnects_anEarlyOneSendsNothing() {
        openSession("81a00006", Duration.ofMinutes(10));
        final Instant now = clock.instant();

        // Paid ten days after expiry with no grace: RESTRICTED before, FULL after.
        assertThat(run(() -> changes.renewed(renewed(now.minus(Duration.ofDays(10)), now.plus(Duration.ofDays(30))))))
                .isEqualTo(1);
        // Paid a day early: FULL before and after; the session's Session-Timeout covers the rest (S4).
        assertThat(run(() -> changes.renewed(renewed(now.plus(Duration.ofDays(1)), now.plus(Duration.ofDays(31))))))
                .isZero();
        // First activation: SubscriptionStateChanged covers it.
        assertThat(run(() -> changes.renewed(renewed(null, now.plus(Duration.ofDays(30)))))).isZero();
        assertThat(requests()).singleElement().satisfies(r -> assertThat(r.get("action").asString()).isEqualTo("DISCONNECT"));
    }

    @Test
    void N4_S10_anUpgradeNowSetsByCoaTheRateTheViewGivesAtLogin() {
        openSession("81a00007", Duration.ofMinutes(10));
        final String faster = api.post("/api/v1/packages", """
                {"name":"Home 25","downloadKbps":25000,"uploadKbps":7500,"durationMinutes":43200,
                 "price":{"amount":400000,"currency":"KES"}}""").json().get("id").asString();
        OwnerSql.client().sql("update subscriptions_subscription set package_id = :p where id = :id")
                .param("p", UUID.fromString(faster)).param("id", lineId).update();

        assertThat(run(() -> changes.packageChanged(packageChanged(faster, "NOW")))).isEqualTo(1);
        assertThat(run(() -> changes.packageChanged(packageChanged(faster, "NEXT_RENEWAL")))).isZero();

        final JsonNode coa = requests().getFirst();
        assertThat(coa.get("action").asString()).isEqualTo("COA");
        assertThat(coa.get("rateLimit").asString()).isEqualTo("7500k/25M").isEqualTo(viewRateLimit());
    }

    @Test
    void N4_N5_aThrottledLineGetsTheNewPackagesThrottleRate() {
        openSession("81a00008", Duration.ofMinutes(10));
        OwnerSql.client().sql("update tenancy_tenant set grace_mode = 'THROTTLED' where id = :id").param("id", tenant).update();
        OwnerSql.client().sql("update subscriptions_subscription set expires_at = now() - interval '1 hour', grace_days = 3 where id = :id")
                .param("id", lineId).update();

        assertThat(run(() -> changes.packageChanged(packageChanged(packageId, "NOW")))).isEqualTo(1);
        assertThat(requests().getFirst().get("rateLimit").asString()).isEqualTo("1M/2M").isEqualTo(viewRateLimit());
    }

    private int run(final Supplier<Integer> work) {
        return TenantContext.callAsTenant(tenant, () -> tx.execute(s -> work.get()));
    }

    private SubscriptionStateChanged stateChanged(final Cause cause) {
        return new SubscriptionStateChanged(UuidV7.next(clock), tenant, clock.instant(), lineId, customerId, "ACTIVE", "PAUSED",
                cause);
    }

    private SubscriptionRenewed renewed(final Instant previousExpiresAt, final Instant expiresAt) {
        return new SubscriptionRenewed(UuidV7.next(clock), tenant, clock.instant(), lineId, UUID.randomUUID(), customerId,
                UUID.fromString(packageId), previousExpiresAt, expiresAt, 250_000, "KES", null);
    }

    private SubscriptionPackageChanged packageChanged(final String toPackageId, final String timing) {
        return new SubscriptionPackageChanged(UuidV7.next(clock), tenant, clock.instant(), lineId, customerId,
                UUID.fromString(packageId), UUID.fromString(toPackageId), timing, 0, "KES");
    }

    private List<JsonNode> requests() {
        return OwnerSql.client().sql("""
                        select payload::text from outbox_event
                        where tenant_id = :t and event_type = 'NetworkAccessChanged' order by id
                        """)
                .param("t", tenant)
                .query(String.class)
                .list()
                .stream()
                .map(JSON::readTree)
                .toList();
    }

    private String viewRateLimit() {
        return OwnerSql.client().sql("""
                        select value from v_radius_auth_v5
                        where username = :u and nasname = :nas and attribute = 'Mikrotik-Rate-Limit'
                        """)
                .param("u", username).param("nas", tunnelIp).query(String.class).single();
    }

    private void openSession(final String acctSessionId, final Duration age) {
        openSessionStartedAt(acctSessionId, clock.instant().minus(age));
    }

    private void openSessionStartedAt(final String acctSessionId, final Instant start) {
        OwnerSql.client().sql("""
                        insert into radacct (acctsessionid, acctuniqueid, username, nasipaddress, acctstarttime)
                        values (:sid, :uid, :u, cast(:nas as inet), :start)
                        """)
                .param("sid", acctSessionId).param("uid", "u-" + acctSessionId + "-" + tenant).param("u", username)
                .param("nas", tunnelIp).param("start", Timestamp.from(start))
                .update();
    }

    private void closedSession(final String acctSessionId) {
        openSession(acctSessionId, Duration.ofHours(2));
        OwnerSql.client().sql("update radacct set acctstoptime = now(), acctterminatecause = 'User-Request' where acctsessionid = :sid and tenant_id = :t")
                .param("sid", acctSessionId).param("t", tenant).update();
    }
}
