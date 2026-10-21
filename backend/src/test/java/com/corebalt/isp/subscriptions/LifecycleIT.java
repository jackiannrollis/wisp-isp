package com.corebalt.isp.subscriptions;

import static org.assertj.core.api.Assertions.assertThat;

import com.corebalt.isp.billing.application.WalletService;
import com.corebalt.isp.shared.money.Money;
import com.corebalt.isp.shared.tenant.TenantContext;
import com.corebalt.isp.subscriptions.api.RenewalResultDto;
import com.corebalt.isp.subscriptions.application.ReminderService;
import com.corebalt.isp.subscriptions.application.RenewalService;
import com.corebalt.isp.subscriptions.application.LifecycleService;
import com.corebalt.isp.support.Api;
import com.corebalt.isp.support.Catalog;
import com.corebalt.isp.support.IntegrationTest;
import com.corebalt.isp.support.OwnerSql;
import com.corebalt.isp.support.TestTenants;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * The lifecycle through the API and services (docs/04): renewal through the WalletCharger port,
 * transitions with Idempotency-Key, SubscriptionStateChanged and audit rows (S9, A1), what RADIUS
 * then answers, and the hourly job's two duties (auto-resume record, reminders).
 */
class LifecycleIT extends IntegrationTest {

    @Value("${local.server.port}")
    int port;

    @Autowired JdbcClient jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired RenewalService renewals;
    @Autowired LifecycleService lifecycle;
    @Autowired ReminderService reminders;
    @Autowired WalletService wallets;

    UUID tenant;
    Api api;
    UUID customerId;
    String username;
    UUID lineId;

    @BeforeEach
    void line() {
        tenant = TestTenants.create(jdbc, tx);
        api = new Api(port, tenant);
        final String packageId = Catalog.pppoePackage(api, "Home 10");
        final String routerId = Catalog.router(api, "Utawala-01").json().get("router").get("id").asString();
        customerId = UUID.fromString(Catalog.customer(api, "Wanjiku Kamau", "0712345678"));
        final JsonNode opened = api.post("/api/v1/subscriptions",
                "{\"customerId\":\"" + customerId + "\",\"packageId\":\"" + packageId + "\",\"routerId\":\"" + routerId + "\"}")
                .json();
        lineId = UUID.fromString(opened.get("subscription").get("id").asString());
        username = opened.get("subscription").get("pppoeUsername").asString();
    }

    @Test
    void B5_renewalWaitsForTheFullPrice() {
        fund(249_999);
        final RenewalResultDto result = renew();
        assertThat(result.outcome()).isEqualTo(RenewalResultDto.Outcome.INSUFFICIENT_BALANCE);
        assertThat(result.subscription().state()).isEqualTo("PENDING");
        assertThat(balance()).isEqualTo(249_999);
    }

    @Test
    void S5_S6_firstRenewalActivatesChargesAndCopiesGraceDays() {
        OwnerSql.client().sql("update tenancy_tenant set default_grace_days = 2 where id = :id").param("id", tenant).update();
        fund(300_000);
        final RenewalResultDto result = renew();
        assertThat(result.outcome()).isEqualTo(RenewalResultDto.Outcome.RENEWED);
        assertThat(result.subscription().state()).isEqualTo("ACTIVE");
        assertThat(result.subscription().accessProfile()).isEqualTo("FULL");
        assertThat(result.subscription().displayStatus()).isEqualTo("ACTIVE");
        assertThat(result.subscription().graceDays()).isEqualTo(2);
        assertThat(Duration.between(result.subscription().activatedAt(), result.subscription().expiresAt()))
                .isEqualTo(Duration.ofDays(30));
        assertThat(balance()).isEqualTo(50_000);
        assertThat(events("SubscriptionRenewed")).hasSize(1);
        assertThat(events("SubscriptionStateChanged")).containsExactly("PENDING>ACTIVE RENEWAL");
    }

    @Test
    void B3_onePaymentRenewsOnlyOneOfTwoLines() {
        final String secondLine = api.post("/api/v1/subscriptions",
                "{\"customerId\":\"" + customerId + "\",\"packageId\":\"" + api.get("/api/v1/packages").json().get("items").get(0).get("id").asString()
                        + "\",\"routerId\":\"" + api.get("/api/v1/routers").json().get("items").get(0).get("id").asString() + "\"}")
                .json().get("subscription").get("id").asString();
        fund(250_000);
        assertThat(renew().outcome()).isEqualTo(RenewalResultDto.Outcome.RENEWED);
        assertThat(TenantContext.callAsTenant(tenant, () -> renewals.renew(UUID.fromString(secondLine))).outcome())
                .isEqualTo(RenewalResultDto.Outcome.INSUFFICIENT_BALANCE);
    }

    @Test
    void S9_transitionsRequireAnIdempotencyKeyAndReplayTheOriginalResponse() {
        activate();
        final String body = "{\"reason\":\"Equipment recovery\"}";
        assertThat(api.post("/api/v1/subscriptions/" + lineId + "/suspend", body).status()).isEqualTo(400);

        final Api.Response first = api.postWithKey("/api/v1/subscriptions/" + lineId + "/suspend", "k-suspend-1", body);
        assertThat(first.status()).isEqualTo(200);
        assertThat(first.json().get("state").asString()).isEqualTo("SUSPENDED");
        assertThat(first.json().get("accessProfile").asString()).isEqualTo("REJECT");

        final Api.Response replay = api.postWithKey("/api/v1/subscriptions/" + lineId + "/suspend", "k-suspend-1", body);
        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.header("Idempotent-Replayed")).isEqualTo("true");
        assertThat(replay.json()).isEqualTo(first.json());
        assertThat(events("SubscriptionStateChanged")).containsExactly("PENDING>ACTIVE RENEWAL", "ACTIVE>SUSPENDED STAFF_ACTION");

        final Api.Response reused =
                api.postWithKey("/api/v1/subscriptions/" + lineId + "/terminate", "k-suspend-1", body);
        assertThat(reused.status()).isEqualTo(422);
        assertThat(reused.body()).contains("idempotency-key-reused");

        final List<String> audit = OwnerSql.client()
                .sql("select action || ' ' || coalesce(reason, '') from identity_audit_log where target_id = :id and action = 'subscriptions.suspended'")
                .param("id", lineId)
                .query(String.class)
                .list();
        assertThat(audit).containsExactly("subscriptions.suspended Equipment recovery");
    }

    @Test
    void S8_aLineThatExpiredDuringTheHoldComesBackRestricted() {
        activate();
        api.postWithKey("/api/v1/subscriptions/" + lineId + "/suspend", "k1", "{\"reason\":\"Fraud check\"}");
        OwnerSql.client()
                .sql("update subscriptions_subscription set expires_at = now() - interval '5 days' where id = :id")
                .param("id", lineId)
                .update();
        final JsonNode lifted =
                api.postWithKey("/api/v1/subscriptions/" + lineId + "/lift-suspension", "k2", "{\"reason\":\"Cleared\"}").json();
        assertThat(lifted.get("state").asString()).isEqualTo("ACTIVE");
        assertThat(lifted.get("accessProfile").asString()).isEqualTo("RESTRICTED");
        assertThat(lifted.get("displayStatus").asString()).isEqualTo("RESTRICTED");
        assertThat(radius("Mikrotik-Address-List")).isEqualTo("restricted");
    }

    @Test
    void S7_pauseIsRestrictedInRadiusAndManualResumeGivesBackTheTimePaused() {
        activate();
        final Instant expiresBefore = Instant.parse(api.get("/api/v1/subscriptions/" + lineId).json().get("expiresAt").asString());
        final Instant until = Instant.now().plus(Duration.ofDays(10));
        final JsonNode paused =
                api.postWithKey("/api/v1/subscriptions/" + lineId + "/pause", "p1", "{\"pauseUntil\":\"" + until + "\"}").json();
        assertThat(paused.get("displayStatus").asString()).isEqualTo("PAUSED");
        assertThat(radius("Mikrotik-Address-List")).isEqualTo("restricted");

        final JsonNode resumed = api.postWithKey("/api/v1/subscriptions/" + lineId + "/resume", "p2", null).json();
        assertThat(resumed.get("state").asString()).isEqualTo("ACTIVE");
        final Instant expiresAfter = Instant.parse(resumed.get("expiresAt").asString());
        assertThat(Duration.between(expiresBefore, expiresAfter)).isBetween(Duration.ZERO, Duration.ofMinutes(1));
        assertThat(events("SubscriptionStateChanged"))
                .containsExactly("PENDING>ACTIVE RENEWAL", "ACTIVE>PAUSED STAFF_ACTION", "PAUSED>ACTIVE STAFF_ACTION");
    }

    @Test
    void S7_pauseLongerThanTheTenantLimitIs409() {
        activate();
        final Instant until = Instant.now().plus(Duration.ofDays(31));
        final Api.Response response =
                api.postWithKey("/api/v1/subscriptions/" + lineId + "/pause", "p1", "{\"pauseUntil\":\"" + until + "\"}");
        assertThat(response.status()).isEqualTo(409);
        assertThat(response.body()).contains("S7: a pause lasts at most 30 days");
    }

    @Test
    void E4_jobRecordsAResumeTheViewAlreadyAppliedWithoutAskingForACoa() {
        activate();
        // A pause of 3 days that ended an hour ago, not yet recorded.
        OwnerSql.client().sql("""
                update subscriptions_subscription
                set state = 'PAUSED', paused_at = now() - interval '3 days 1 hour', pause_until = now() - interval '1 hour'
                where id = :id
                """).param("id", lineId).update();
        final Instant viewExpiry = Instant.parse(api.get("/api/v1/subscriptions/" + lineId).json().get("expiresAt").asString())
                .plus(Duration.ofDays(3));
        assertThat(api.get("/api/v1/subscriptions/" + lineId).json().get("accessProfile").asString()).isEqualTo("FULL");
        assertThat(radius("Framed-Pool")).isEqualTo("full");

        TenantContext.runAsTenant(tenant, () -> lifecycle.recordAutoResume(lineId));

        final JsonNode recorded = api.get("/api/v1/subscriptions/" + lineId).json();
        assertThat(recorded.get("state").asString()).isEqualTo("ACTIVE");
        assertThat(Instant.parse(recorded.get("expiresAt").asString())).isEqualTo(viewExpiry);
        assertThat(events("SubscriptionStateChanged")).last().isEqualTo("PAUSED>ACTIVE AUTO_RESUME_RECORDED");
    }

    @Test
    void terminationRevokesThePasswordAndRejects() {
        activate();
        final JsonNode terminated =
                api.postWithKey("/api/v1/subscriptions/" + lineId + "/terminate", "t1", "{\"reason\":\"Moved away\"}").json();
        assertThat(terminated.get("state").asString()).isEqualTo("TERMINATED");
        assertThat(radius("Auth-Type")).isEqualTo("Reject");
        assertThat(radius("Cleartext-Password")).isNull();
        assertThat(radius("Reply-Message")).isEqualTo("Account terminated");
        assertThat(api.postWithKey("/api/v1/subscriptions/" + lineId + "/lift-suspension", "t2", "{\"reason\":\"x\"}").status())
                .isEqualTo(409);
    }

    @Test
    void M1_remindersAreRaisedOncePerEventAndExpiry() {
        activate();
        OwnerSql.client()
                .sql("update subscriptions_subscription set expires_at = now() + interval '2 hours', grace_days = 0 where id = :id")
                .param("id", lineId)
                .update();
        // Within two hours of expiry: today or tomorrow in Nairobi, so DUE_TODAY or EXPIRING_1D.
        final int first = TenantContext.callAsTenant(tenant, () -> reminders.raiseDue());
        final int again = TenantContext.callAsTenant(tenant, () -> reminders.raiseDue());
        assertThat(first).isOne();
        assertThat(again).isZero();
        final List<Map<String, Object>> due = OwnerSql.client()
                .sql("select payload ->> 'eventKey' as event, payload ->> 'dedupeKey' as dedupe from outbox_event where event_type = 'SubscriptionReminderDue' and payload ->> 'subscriptionId' = :id")
                .param("id", lineId.toString())
                .query()
                .listOfRows();
        assertThat(due).hasSize(1);
        assertThat((String) due.getFirst().get("event")).isIn("DUE_TODAY", "EXPIRING_1D");
        assertThat((String) due.getFirst().get("dedupe")).startsWith(due.getFirst().get("event") + ":" + lineId + ":");
    }

    /** Credits the wallet as staff would (an ADJUSTMENT with a reason); the renewal is run by the test. */
    private void fund(final long minorUnits) {
        TenantContext.runAsTenant(tenant, () -> tx.executeWithoutResult(
                s -> wallets.adjust(customerId, Money.ofMinor(minorUnits, "KES"), "test funding")));
    }

    private long balance() {
        return TenantContext.callAsTenant(tenant, () -> wallets.balance(customerId, Money.KES).minorUnits());
    }

    private RenewalResultDto renew() {
        return TenantContext.callAsTenant(tenant, () -> renewals.renew(lineId));
    }

    private void activate() {
        fund(250_000);
        assertThat(renew().outcome()).isEqualTo(RenewalResultDto.Outcome.RENEWED);
    }

    /** One reply or check attribute FreeRADIUS would read for this line (any NAS of the tenant). */
    private String radius(final String attribute) {
        return OwnerSql.client()
                .sql("select max(value) from v_radius_auth_v7 where username = :username and attribute = :attribute")
                .param("username", username)
                .param("attribute", attribute)
                .query((rs, row) -> java.util.Optional.ofNullable(rs.getString(1)))
                .single()
                .orElse(null);
    }

    /** {@code from>to cause} for state changes, or event ids for other types, in order. */
    private List<String> events(final String type) {
        return OwnerSql.client()
                .sql("""
                        select case when event_type = 'SubscriptionStateChanged'
                                    then (payload ->> 'fromState') || '>' || (payload ->> 'toState') || ' ' || (payload ->> 'cause')
                                    else id::text end
                        from outbox_event
                        where event_type = :type and payload ->> 'subscriptionId' = :id
                        order by occurred_at, id
                        """)
                .param("type", type)
                .param("id", lineId.toString())
                .query(String.class)
                .list();
    }
}
