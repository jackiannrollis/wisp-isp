package com.corebalt.isp.subscriptions;

import static org.assertj.core.api.Assertions.assertThat;

import com.corebalt.isp.subscriptions.domain.AccessPolicy;
import com.corebalt.isp.subscriptions.domain.AccessProfile;
import com.corebalt.isp.subscriptions.domain.AccessReason;
import com.corebalt.isp.subscriptions.domain.DisplayStatus;
import com.corebalt.isp.subscriptions.domain.FairUse;
import com.corebalt.isp.subscriptions.domain.GraceMode;
import com.corebalt.isp.subscriptions.domain.SubscriptionState;
import com.corebalt.isp.support.IntegrationTest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * S3: {@code AccessPolicy} and {@code v_radius_auth_v7} return the same profile and the same
 * Session-Timeout for the same inputs at the same instant, and {@code DisplayStatus} and the SQL
 * function {@code subscription_line_access()} (which {@code v_subscription_status} uses, on top of
 * {@code subscription_access_v3()}, itself on top of the {@code subscription_access_v2()} that v6 uses) the
 * same profile, next change, display status and access reason (S1).
 * The inputs include each line's cycle usage against its package's fair-use allowance and data cap
 * (docs/09 step 8b): below, at the allowance, at the cap, a counter left from an older cycle, and a line
 * whose cycle start is unknown. Java derives the two usage flags with {@link FairUse}, as the
 * application does. Every case is inserted and the views read in one transaction, so their now() and
 * the instant given to AccessPolicy are identical; the transaction is rolled back. Must stay green on
 * every migration touching either side (conventions.md, Tests).
 */
class SubscriptionAccessAgreementIT extends IntegrationTest {

    static final String[] OFFSETS = {
        "-10 days", "-2 days -1 hour", "-2 days", "-2 days +1 hour", "-1 hour", "-1 second",
        "0 seconds", "+1 second", "+1 hour", "+23 hours", "+1 day +1 hour", "+2 days +1 hour", "+5 days"
    };

    /** The package's limits in every case: download plus upload bytes in the cycle. */
    static final long FAIR_USE_BYTES = 1_000;
    static final long DATA_CAP_BYTES = 5_000;

    /** A line's usage: NONE is just below the allowance; STALE a counter from an older cycle; UNKNOWN no cycle start. */
    enum Usage { NONE, FAIR_USE, DATA_CAP, STALE, UNKNOWN }

    record Case(
            String username, String state, String expiresIn, String shift, int graceDays, String pausedAgo, String pauseLeft,
            Usage usage, String lineGraceMode) {

        Case(final String username, final String state, final String expiresIn, final int graceDays,
                final String pausedAgo, final String pauseLeft) {
            this(username, state, expiresIn, "0 seconds", graceDays, pausedAgo, pauseLeft, Usage.NONE, null);
        }

        Case(final String username, final String state, final String expiresIn, final String shift, final int graceDays,
                final String pausedAgo, final String pauseLeft) {
            this(username, state, expiresIn, shift, graceDays, pausedAgo, pauseLeft, Usage.NONE, null);
        }

        Case(final String username, final String state, final String expiresIn, final String shift, final int graceDays,
                final String pausedAgo, final String pauseLeft, final Usage usage) {
            this(username, state, expiresIn, shift, graceDays, pausedAgo, pauseLeft, usage, null);
        }
    }

    @Test
    void S3_accessPolicyAndTheViewAgreeOnProfileAndSessionTimeout() throws Exception {
        try (Connection db = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            db.setAutoCommit(false);
            try {
                final Map<GraceMode, String> codes = Map.of(GraceMode.FULL_SPEED, "ZQF", GraceMode.THROTTLED, "ZQT");
                final Map<String, GraceMode> modeByUsername = new HashMap<>();
                final Map<String, Usage> usageByUsername = new HashMap<>();
                int compared = 0;
                for (final GraceMode mode : GraceMode.values()) {
                    final String code = codes.get(mode);
                    final List<Case> cases = cases(code);
                    insertTenant(db, code, mode, cases);
                    cases.forEach(c -> {
                        modeByUsername.put(c.username(), mode);
                        usageByUsername.put(c.username(), c.usage());
                    });
                }
                final Instant now = dbNow(db);
                final Map<String, AccessPolicy.Facts> facts = readFacts(db);
                // Each line is judged by its own grace mode when it has one (S6), as the application does.
                modeByUsername.putAll(readModes(db));
                final Map<String, Map<String, String>> view = readView(db);
                final Map<String, String[]> function = readFunction(db, now);
                final Map<String, String> viewStatus = readStatusView(db);

                for (final Map.Entry<String, AccessPolicy.Facts> line : facts.entrySet()) {
                    final String username = line.getKey();
                    final AccessPolicy.Decision decision =
                            AccessPolicy.evaluate(line.getValue(), modeByUsername.get(username), now);
                    final Map<String, String> attributes = view.get(username);
                    assertThat(attributes).as("view rows for %s", username).isNotNull();
                    assertThat(profileOf(attributes)).as("profile of %s %s", username, line.getValue())
                            .isEqualTo(decision.profile());
                    final Long timeout = decision.sessionTimeoutSeconds(now);
                    assertThat(attributes.get("Session-Timeout"))
                            .as("Session-Timeout of %s %s", username, line.getValue())
                            .isEqualTo(timeout == null ? null : timeout.toString());
                    final String[] sql = function.get(username);
                    assertThat(sql[0]).as("function profile of %s %s", username, line.getValue())
                            .isEqualTo(decision.profile().name());
                    assertThat(sql[1]).as("function display status of %s %s", username, line.getValue())
                            .isEqualTo(DisplayStatus.of(line.getValue(), decision, now).name());
                    assertThat(sql[2]).as("function next change of %s %s", username, line.getValue())
                            .isEqualTo(decision.nextChangeAt() == null ? null : decision.nextChangeAt().toString());
                    final AccessReason reason = AccessReason.of(line.getValue(), decision, now);
                    assertThat(sql[3]).as("function access reason of %s %s", username, line.getValue())
                            .isEqualTo(reason == null ? null : reason.name());
                    final String listed = viewStatus.get(username);
                    assertThat(listed).as("v_subscription_status of %s", username).isEqualTo(sql[1]);
                    compared++;
                }
                assertThat(compared).isGreaterThan(400);

                // The usage inputs really bite: an active line before expiry is throttled at the
                // allowance and restricted at the cap, and an older cycle or an unknown start count as
                // nothing (docs/09 step 8b, Q13, Q14).
                for (final String code : codes.values()) {
                    assertThat(profileOf(view.get(probe(code, Usage.FAIR_USE)))).isEqualTo(AccessProfile.THROTTLED);
                    assertThat(profileOf(view.get(probe(code, Usage.DATA_CAP)))).isEqualTo(AccessProfile.RESTRICTED);
                    assertThat(profileOf(view.get(probe(code, Usage.STALE)))).isEqualTo(AccessProfile.FULL);
                    assertThat(profileOf(view.get(probe(code, Usage.UNKNOWN)))).isEqualTo(AccessProfile.FULL);
                    assertThat(profileOf(view.get(probe(code, Usage.NONE)))).isEqualTo(AccessProfile.FULL);
                    assertThat(viewStatus.get(probe(code, Usage.FAIR_USE))).isEqualTo("THROTTLED");
                    assertThat(viewStatus.get(probe(code, Usage.DATA_CAP))).isEqualTo("CAPPED");
                    assertThat(viewStatus.get(probe(code, Usage.NONE))).isEqualTo("ACTIVE");
                }

                // The three forms of one pause agree with each other too: paused with pause_until
                // ahead (RESTRICTED), paused past pause_until (resumed by the view) and the resume
                // recorded by the job; the last two carry the same shifted expiry.
                for (final String code : codes.values()) {
                    for (int i = 0; i < OFFSETS.length; i++) {
                        final AccessPolicy.Facts over = facts.get(code + "000001-" + (500 + i));
                        final AccessPolicy.Facts recorded = facts.get(code + "000001-" + (600 + i));
                        final GraceMode mode = modeByUsername.get(code + "000001-" + (500 + i));
                        assertThat(AccessPolicy.evaluate(over, mode, now))
                                .isEqualTo(AccessPolicy.evaluate(recorded, mode, now));
                        assertThat(view.get(code + "000001-" + (500 + i)))
                                .isEqualTo(view.get(code + "000001-" + (600 + i)));
                        assertThat(profileOf(view.get(code + "000001-" + (400 + i)))).isEqualTo(AccessProfile.RESTRICTED);
                    }
                }
            } finally {
                db.rollback();
            }
        }
    }

    /**
     * States x expiry offsets around every boundary x grace 0 and 2, without usage; ACTIVE and PAUSED
     * lines again for every usage; three forms of a pause; and one line per usage expiring in 5 days.
     */
    private static List<Case> cases(final String code) {
        final List<Case> cases = new ArrayList<>();
        int n = 2;
        cases.add(new Case(code + "000001", "PENDING", null, 0, null, null));
        for (final String state : List.of("ACTIVE", "PENDING", "SUSPENDED", "TERMINATED")) {
            for (final String offset : OFFSETS) {
                for (final int grace : new int[] {0, 2}) {
                    cases.add(new Case(code + "000001-" + n++, state, state.equals("PENDING") ? null : offset, grace, null, null));
                }
            }
        }
        for (final Usage usage : Usage.values()) {
            if (usage == Usage.NONE) {
                continue;
            }
            for (final String offset : OFFSETS) {
                for (final int grace : new int[] {0, 2}) {
                    cases.add(new Case(code + "000001-" + n++, "ACTIVE", offset, "0 seconds", grace, null, null, usage));
                }
            }
            // A pause still restricts, and a pause that ran out resumes into the usage rules.
            cases.add(new Case(code + "000001-" + n++, "PAUSED", "+5 days", "0 seconds", 2, "1 day", "1 day", usage));
            cases.add(new Case(code + "000001-" + n++, "PAUSED", "+5 days", "0 seconds", 2, "3 days", "-1 day", usage));
        }
        for (int i = 0; i < OFFSETS.length; i++) {
            final String offset = OFFSETS[i];
            // The same 2-day pause, seen before its end, after its end, and after it is recorded.
            cases.add(new Case(code + "000001-" + (400 + i), "PAUSED", offset, 2, "1 day", "1 day"));
            cases.add(new Case(code + "000001-" + (500 + i), "PAUSED", offset, 2, "3 days", "-1 day"));
            cases.add(new Case(code + "000001-" + (600 + i), "ACTIVE", offset, "2 days", 2, null, null));
        }
        // Pause ending within the hour: Session-Timeout runs to pause_until.
        cases.add(new Case(code + "000001-700", "PAUSED", "+3 days", 0, "1 day", "30 minutes"));
        for (final Usage usage : Usage.values()) {
            cases.add(new Case(probe(code, usage), "ACTIVE", "+5 days", "0 seconds", 2, null, null, usage));
        }
        // Lines renewed under the other grace mode keep it (S6): the line's mode wins over the tenant's.
        final String other = code.equals("ZQF") ? "THROTTLED" : "FULL_SPEED";
        for (final String offset : OFFSETS) {
            cases.add(new Case(code + "000003-" + n++, "ACTIVE", offset, "0 seconds", 2, null, null, Usage.NONE, other));
            cases.add(new Case(code + "000003-" + n++, "ACTIVE", offset, "0 seconds", 2, null, null, Usage.FAIR_USE, other));
        }
        return cases;
    }

    /** One ACTIVE line per usage, five days before expiry, numbered as PPPoE usernames must be (T2). */
    private static String probe(final String code, final Usage usage) {
        return code + "000002-" + (usage.ordinal() + 1);
    }

    private static void insertTenant(final Connection db, final String code, final GraceMode mode, final List<Case> cases)
            throws Exception {
        final UUID tenant = UUID.randomUUID();
        final UUID customer = UUID.randomUUID();
        final UUID pkg = UUID.randomUUID();
        exec(db, "insert into tenancy_tenant (id, code, name, subdomain, status, grace_mode) values (?, ?, ?, ?, 'ACTIVE', ?)",
                tenant, code, "Agreement " + code, code.toLowerCase(), mode.name());
        exec(db, "insert into customers_customer (id, tenant_id, number, name, phone) values (?, ?, ?, 'Agreement', '+254700000000')",
                customer, tenant, code + "000001");
        exec(db, """
                insert into packages_package (id, tenant_id, name, download_kbps, upload_kbps, duration_minutes, price_minor,
                                              throttle_download_kbps, throttle_upload_kbps, fup_bytes, data_cap_bytes)
                values (?, ?, 'Agreement', 10000, 5000, 43200, 100000, 2000, 1000, ?, ?)
                """, pkg, tenant, FAIR_USE_BYTES, DATA_CAP_BYTES);
        final UUID router = UUID.randomUUID();
        exec(db, "insert into network_router (id, tenant_id, name, tunnel_ip_a) values (?, ?, 'agreement', cast(? as inet))",
                router, tenant, mode == GraceMode.THROTTLED ? "10.254.0.2" : "10.254.0.1");
        try (PreparedStatement insert = db.prepareStatement("""
                insert into subscriptions_subscription
                    (id, tenant_id, customer_id, package_id, router_id, pppoe_username, state, expires_at, grace_days,
                     paused_at, pause_until, terminated_at, cycle_started_at, grace_mode)
                values (?, ?, ?, ?, ?, ?, ?, now() + cast(? as interval) + cast(? as interval), ?,
                        now() - cast(? as interval), now() + cast(? as interval),
                        case when ? = 'TERMINATED' then now() end,
                        case when ? = 'UNKNOWN' then null else now() - interval '1 day' end, ?)
                """);
             PreparedStatement usage = db.prepareStatement("""
                insert into network_line_usage (tenant_id, subscription_id, cycle_started_at, octets, updated_at)
                values (?, ?, now() - cast(? as interval), ?, now())
                """)) {
            for (final Case c : cases) {
                final UUID id = UUID.randomUUID();
                insert.setObject(1, id);
                insert.setObject(2, tenant);
                insert.setObject(3, customer);
                insert.setObject(4, pkg);
                insert.setObject(5, router);
                insert.setString(6, c.username());
                insert.setString(7, c.state());
                insert.setString(8, c.expiresIn());
                insert.setString(9, c.shift());
                insert.setInt(10, c.graceDays());
                insert.setString(11, c.pausedAgo());
                insert.setString(12, c.pauseLeft());
                insert.setString(13, c.state());
                insert.setString(14, c.usage().name());
                insert.setString(15, c.lineGraceMode());
                insert.addBatch();
                // NONE sits one byte under the allowance; STALE and UNKNOWN carry more than the cap.
                final String cycle = c.usage() == Usage.STALE ? "40 days" : "1 day";
                final long octets = switch (c.usage()) {
                    case NONE -> FAIR_USE_BYTES - 1;
                    case FAIR_USE -> FAIR_USE_BYTES;
                    case DATA_CAP -> DATA_CAP_BYTES;
                    case STALE, UNKNOWN -> DATA_CAP_BYTES * 2;
                };
                usage.setObject(1, tenant);
                usage.setObject(2, id);
                usage.setString(3, cycle);
                usage.setLong(4, octets);
                usage.addBatch();
            }
            insert.executeBatch();
            usage.executeBatch();
        }
    }

    private static Instant dbNow(final Connection db) throws Exception {
        try (ResultSet rs = db.createStatement().executeQuery("select now()")) {
            rs.next();
            return rs.getTimestamp(1).toInstant();
        }
    }

    /** The stored facts, and the usage flags as the application derives them (SubscriptionViews, LineUsage). */
    private static Map<String, AccessPolicy.Facts> readFacts(final Connection db) throws Exception {
        final Map<String, AccessPolicy.Facts> facts = new LinkedHashMap<>();
        try (ResultSet rs = db.createStatement().executeQuery("""
                select s.pppoe_username, s.state, s.expires_at, s.grace_days, s.paused_at, s.pause_until,
                       s.cycle_started_at, u.cycle_started_at, u.octets, p.fup_bytes, p.data_cap_bytes
                from subscriptions_subscription s
                join packages_package p on p.id = s.package_id
                left join network_line_usage u on u.subscription_id = s.id
                where s.pppoe_username like 'ZQ%'
                """)) {
            while (rs.next()) {
                final Instant lineCycle = instant(rs.getTimestamp(7));
                final Instant counterCycle = instant(rs.getTimestamp(8));
                final long octets = lineCycle != null && Objects.equals(lineCycle, counterCycle) ? rs.getLong(9) : 0;
                final FairUse.Limits limits = FairUse.limits(
                        (Long) rs.getObject(10), (Long) rs.getObject(11), octets);
                facts.put(rs.getString(1), new AccessPolicy.Facts(
                        SubscriptionState.valueOf(rs.getString(2)),
                        instant(rs.getTimestamp(3)),
                        rs.getInt(4),
                        instant(rs.getTimestamp(5)),
                        instant(rs.getTimestamp(6))).withUsage(limits));
            }
        }
        return facts;
    }

    private static Map<String, GraceMode> readModes(final Connection db) throws Exception {
        final Map<String, GraceMode> modes = new HashMap<>();
        try (ResultSet rs = db.createStatement().executeQuery("select s.pppoe_username, coalesce(s.grace_mode, t.grace_mode) "
                + "from subscriptions_subscription s join tenancy_tenant t on t.id = s.tenant_id where s.pppoe_username like 'ZQ%'")) {
            while (rs.next()) {
                modes.put(rs.getString(1), GraceMode.valueOf(rs.getString(2)));
            }
        }
        return modes;
    }

    private static Map<String, Map<String, String>> readView(final Connection db) throws Exception {
        final Map<String, Map<String, String>> view = new HashMap<>();
        try (ResultSet rs = db.createStatement().executeQuery(
                "select username, attribute, value from v_radius_auth_v7 where username like 'ZQ%'")) {
            while (rs.next()) {
                view.computeIfAbsent(rs.getString(1), k -> new HashMap<>()).put(rs.getString(2), rs.getString(3));
            }
        }
        return view;
    }

    /** Profile, display status and next change from the SQL function, at the instant given to AccessPolicy. */
    private static Map<String, String[]> readFunction(final Connection db, final Instant now) throws Exception {
        final Map<String, String[]> rows = new HashMap<>();
        try (PreparedStatement statement = db.prepareStatement("""
                select s.pppoe_username, a.profile, a.display_status, a.next_change_at, a.access_reason
                from subscriptions_subscription s
                cross join lateral subscription_line_access(s.id, ?) a
                where s.pppoe_username like 'ZQ%'
                """)) {
            statement.setTimestamp(1, Timestamp.from(now));
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    final Timestamp next = rs.getTimestamp(4);
                    rows.put(rs.getString(1), new String[] {
                        rs.getString(2), rs.getString(3), next == null ? null : next.toInstant().toString(), rs.getString(5)});
                }
            }
        }
        return rows;
    }

    /** v_subscription_status reads now(), the same instant in this transaction. */
    private static Map<String, String> readStatusView(final Connection db) throws Exception {
        final Map<String, String> rows = new HashMap<>();
        try (ResultSet rs = db.createStatement().executeQuery(
                "select pppoe_username, display_status from v_subscription_status where pppoe_username like 'ZQ%'")) {
            while (rs.next()) {
                rows.put(rs.getString(1), rs.getString(2));
            }
        }
        return rows;
    }

    /** The profile the view's attributes encode (docs/06 Profiles and reply attributes). */
    static AccessProfile profileOf(final Map<String, String> attributes) {
        if ("Reject".equals(attributes.get("Auth-Type"))) {
            return AccessProfile.REJECT;
        }
        final String list = attributes.get("Mikrotik-Address-List");
        if ("restricted".equals(list)) {
            return AccessProfile.RESTRICTED;
        }
        return "throttled".equals(list) ? AccessProfile.THROTTLED : AccessProfile.FULL;
    }

    private static Instant instant(final Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static void exec(final Connection db, final String sql, final Object... params) throws Exception {
        try (PreparedStatement statement = db.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                statement.setObject(i + 1, params[i]);
            }
            statement.executeUpdate();
        }
    }
}
