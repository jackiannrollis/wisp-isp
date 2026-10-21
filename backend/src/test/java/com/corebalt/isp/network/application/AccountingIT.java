package com.corebalt.isp.network.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.corebalt.isp.shared.tenant.TenantContext;
import com.corebalt.isp.support.Api;
import com.corebalt.isp.support.Catalog;
import com.corebalt.isp.support.IntegrationTest;
import com.corebalt.isp.support.OwnerSql;
import com.corebalt.isp.support.TestTenants;
import com.corebalt.isp.tenancy.api.TenantProfile;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Accounting upkeep (docs/06 Accounting, N7) over sessions written as FreeRADIUS would write them
 * (as the owner, through the resolve trigger): roll-up, stale sessions, partitions, last heard, RLS.
 */
class AccountingIT extends IntegrationTest {

    @Value("${local.server.port}")
    int port;

    @Autowired JdbcClient jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired Accounting accounting;
    @Autowired TenantProfile tenantProfile;
    @Autowired CycleUsage cycleUsage;

    TestTenants.Created tenant;
    String tunnelIp;
    UUID routerId;
    String username;
    UUID subscriptionId;

    @BeforeEach
    void lineOnARouter() {
        tenant = TestTenants.createWithCode(jdbc, tx);
        final Api api = new Api(port, tenant.id());
        final var router = Catalog.router(api, "Kitengela-01").json().get("router");
        routerId = UUID.fromString(router.get("id").asString());
        tunnelIp = router.get("tunnelIp").asString();
        final String customer = Catalog.customer(api, "Otieno Ouma", "0712" + String.format("%06d", (int) (Math.random() * 1_000_000)));
        final var line = api.post("/api/v1/subscriptions", "{\"customerId\":\"" + customer + "\",\"packageId\":\""
                + Catalog.pppoePackage(api, "Home 10") + "\",\"routerId\":\"" + routerId + "\"}").json().get("subscription");
        username = line.get("pppoeUsername").asString();
        subscriptionId = UUID.fromString(line.get("id").asString());
    }

    @Test
    void N7_theRollUpCountsEachSessionsGrowthOnce() {
        final String session = session(Instant.now().minusSeconds(600), Instant.now().minusSeconds(60), 1_000, 5_000);
        assertThat(rollUp()).isOne();
        assertThat(rollUp()).as("nothing grew").isZero();
        assertThat(daily()).containsEntry("input_octets", 1_000L).containsEntry("output_octets", 5_000L);

        grow(session, 4_000, 25_000);
        rollUp();
        assertThat(daily()).containsEntry("input_octets", 4_000L).containsEntry("output_octets", 25_000L);

        // A counter that goes backwards (a router bug) never subtracts usage.
        grow(session, 10, 10);
        rollUp();
        assertThat(daily()).containsEntry("input_octets", 4_000L).containsEntry("output_octets", 25_000L);
    }

    @Test
    void N7_growthIsAttributedToTheTenantLocalDayTheRollUpRunsIn() {
        session(Instant.now().minusSeconds(600), Instant.now().minusSeconds(60), 2_000, 3_000);
        // 22:30 UTC is 01:30 the next day in Nairobi (UTC+3), the tenant's zone.
        final Instant late = LocalDate.now(ZoneOffset.UTC).atTime(22, 30).toInstant(ZoneOffset.UTC);
        final Accounting at = new Accounting(jdbc, tenantProfile, cycleUsage, Clock.fixed(late, ZoneOffset.UTC));
        TenantContext.runAsTenant(tenant.id(), () -> tx.executeWithoutResult(s -> at.rollUpUsage()));
        final LocalDate expected = LocalDate.ofInstant(late, TenantContext.callAsTenant(tenant.id(),
                () -> tx.execute(s -> tenantProfile.currentPolicy().zone())));
        assertThat(expected).isEqualTo(LocalDate.now(ZoneOffset.UTC).plusDays(1));
        assertThat(OwnerSql.client().sql("select day from network_usage_daily where subscription_id = :s")
                .param("s", subscriptionId).query(LocalDate.class).single()).isEqualTo(expected);
    }

    @Test
    void aSessionSilentFor15MinutesIsClosedAsStaleAndAnInterimReopensIt() {
        final String stale = session(Instant.now().minusSeconds(3600), Instant.now().minus(Duration.ofMinutes(16)), 1, 1);
        final String alive = session(Instant.now().minusSeconds(3600), Instant.now().minus(Duration.ofMinutes(14)), 1, 1);
        assertThat(TenantContext.callAsTenant(tenant.id(), accounting::closeStaleSessions)).isOne();
        final Map<String, Object> closed = row(stale);
        assertThat(closed).containsEntry("acctterminatecause", "Stale");
        assertThat(closed.get("acctstoptime")).isEqualTo(closed.get("acctupdatetime"));
        assertThat(row(alive).get("acctstoptime")).isNull();

        // What FreeRADIUS's interim query does when the router reports the session again.
        OwnerSql.client().sql("""
                        update radacct set acctupdatetime = now(), acctstoptime = null, acctterminatecause = null
                        where acctuniqueid = :id and (acctstoptime is null or acctterminatecause = 'Stale')
                        """).param("id", stale).update();
        assertThat(row(stale).get("acctstoptime")).isNull();
    }

    @Test
    void N7_partitionsAreCreatedAheadMoveRowsOutOfDefaultAndExpireAfter13Months() {
        final YearMonth now = YearMonth.now(ZoneOffset.UTC);
        // A session for a month with no partition yet lands in DEFAULT ...
        final Instant far = now.plusMonths(5).atDay(2).atStartOfDay().toInstant(ZoneOffset.UTC);
        final String early = session(far, far, 0, 0);
        assertThat(partitionOf(early)).isEqualTo("radacct_default");
        // ... and moves into its month when the month is created.
        OwnerSql.client().sql("select network_ensure_radacct_partitions(5)").query(Integer.class).single();
        assertThat(partitionOf(early)).isEqualTo("radacct_p" + now.plusMonths(5).format(DateTimeFormatter.ofPattern("yyyyMM")));
        assertThat(partitions()).contains("radacct_p" + now.format(DateTimeFormatter.ofPattern("yyyyMM")),
                "radacct_p" + now.plusMonths(3).format(DateTimeFormatter.ofPattern("yyyyMM")));

        final YearMonth expired = now.minusMonths(14);
        final YearMonth kept = now.minusMonths(13);
        for (final YearMonth month : List.of(expired, kept)) {
            OwnerSql.client().sql(("create table if not exists radacct_p%s partition of radacct "
                    + "for values from ('%s') to ('%s')").formatted(month.format(DateTimeFormatter.ofPattern("yyyyMM")),
                    month.atDay(1), month.plusMonths(1).atDay(1))).update();
        }
        final Instant old = expired.atDay(3).atStartOfDay().toInstant(ZoneOffset.UTC);
        session(old, old, 0, 0);

        assertThat(TenantContext.callAsPlatform("test", accounting::dropExpiredPartitions)).isGreaterThanOrEqualTo(1);
        assertThat(partitions()).doesNotContain("radacct_p" + expired.format(DateTimeFormatter.ofPattern("yyyyMM")))
                .contains("radacct_p" + kept.format(DateTimeFormatter.ofPattern("yyyyMM")));
        assertThat(TenantContext.callAsPlatform("test", accounting::ensurePartitions)).isZero();
    }

    @Test
    void T1_aTenantSeesOnlyItsOwnSessions() {
        session(Instant.now().minusSeconds(60), Instant.now(), 1, 1);
        final UUID other = TestTenants.create(jdbc, tx);
        assertThat(visibleSessions(other)).isZero();
        assertThat(visibleSessions(tenant.id())).isOne();
    }

    private long visibleSessions(final UUID tenantId) {
        final Long count = TenantContext.callAsTenant(tenantId, () -> tx.execute(s ->
                jdbc.sql("select count(*) from radacct where username = :u").param("u", username).query(Long.class).single()));
        return count;
    }

    @Test
    void accountingIsTheRoutersSecondaryLastHeard() {
        session(Instant.now().minusSeconds(600), Instant.now().minusSeconds(30), 1, 1);
        assertThat(TenantContext.callAsTenant(tenant.id(), accounting::recordLastHeard)).isOne();
        assertThat(OwnerSql.client().sql("select last_accounting_at > now() - interval '1 minute' from network_router where id = :id")
                .param("id", routerId).query(Boolean.class).single()).isTrue();
    }

    // --- helpers ---

    /** An open session written as FreeRADIUS would (owner, through the resolve trigger). */
    private String session(final Instant start, final Instant updated, final long input, final long output) {
        final String id = UUID.randomUUID().toString();
        OwnerSql.client().sql("""
                        insert into radacct (acctsessionid, acctuniqueid, username, nasipaddress, acctstarttime, acctupdatetime,
                                             acctinputoctets, acctoutputoctets)
                        values (:id, :id, :username, cast(:nas as inet), :start, :updated, :input, :output)
                        """)
                .param("id", id).param("username", username).param("nas", tunnelIp)
                .param("start", Timestamp.from(start)).param("updated", Timestamp.from(updated))
                .param("input", input).param("output", output)
                .update();
        return id;
    }

    private void grow(final String session, final long input, final long output) {
        OwnerSql.client().sql("update radacct set acctinputoctets = :i, acctoutputoctets = :o, acctupdatetime = now() where acctuniqueid = :id")
                .param("i", input).param("o", output).param("id", session).update();
    }

    private int rollUp() {
        return TenantContext.callAsTenant(tenant.id(), accounting::rollUpUsage);
    }

    private Map<String, Object> daily() {
        return OwnerSql.client().sql("select input_octets, output_octets from network_usage_daily where subscription_id = :s")
                .param("s", subscriptionId).query().singleRow();
    }

    private static Map<String, Object> row(final String session) {
        return OwnerSql.client().sql("select acctstoptime, acctupdatetime, acctterminatecause from radacct where acctuniqueid = :id")
                .param("id", session).query().singleRow();
    }

    private static String partitionOf(final String session) {
        return OwnerSql.client().sql("select tableoid::regclass::text from radacct where acctuniqueid = :id")
                .param("id", session).query(String.class).single();
    }

    private static List<String> partitions() {
        return OwnerSql.client().sql("""
                        select c.relname from pg_inherits i join pg_class c on c.oid = i.inhrelid
                        where i.inhparent = 'radacct'::regclass
                        """).query(String.class).list();
    }
}
