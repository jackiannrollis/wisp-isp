package com.corebalt.isp.subscriptions;

import static org.assertj.core.api.Assertions.assertThat;

import com.corebalt.isp.subscriptions.domain.AccessPolicy;
import com.corebalt.isp.subscriptions.domain.AccessProfile;
import com.corebalt.isp.subscriptions.domain.DisplayStatus;
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
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * S3: {@code AccessPolicy} and {@code v_radius_auth_v5} return the same profile and the same
 * Session-Timeout for the same inputs at the same instant, and {@code DisplayStatus} and the SQL
 * function {@code subscription_access()} (which v5 and {@code v_subscription_status} use) the same
 * profile, next change and display status (S1). Every case is inserted and the view read
 * in one transaction, so the view's now() and the instant given to AccessPolicy are identical; the
 * transaction is rolled back. Must stay green on every migration touching either side
 * (conventions.md, Tests).
 */
class SubscriptionAccessAgreementIT extends IntegrationTest {

    static final String[] OFFSETS = {
        "-10 days", "-2 days -1 hour", "-2 days", "-2 days +1 hour", "-1 hour", "-1 second",
        "0 seconds", "+1 second", "+1 hour", "+23 hours", "+1 day +1 hour", "+2 days +1 hour", "+5 days"
    };

    record Case(
            String username, String state, String expiresIn, String shift, int graceDays, String pausedAgo, String pauseLeft) {

        Case(final String username, final String state, final String expiresIn, final int graceDays,
                final String pausedAgo, final String pauseLeft) {
            this(username, state, expiresIn, "0 seconds", graceDays, pausedAgo, pauseLeft);
        }
    }

    @Test
    void S3_accessPolicyAndTheViewAgreeOnProfileAndSessionTimeout() throws Exception {
        try (Connection db = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            db.setAutoCommit(false);
            try {
                final Map<GraceMode, String> codes = Map.of(GraceMode.FULL_SPEED, "ZQF", GraceMode.THROTTLED, "ZQT");
                final Map<String, GraceMode> modeByUsername = new HashMap<>();
                int compared = 0;
                for (final GraceMode mode : GraceMode.values()) {
                    final String code = codes.get(mode);
                    final List<Case> cases = cases(code);
                    insertTenant(db, code, mode, cases);
                    cases.forEach(c -> modeByUsername.put(c.username(), mode));
                }
                final Instant now = dbNow(db);
                final Map<String, AccessPolicy.Facts> facts = readFacts(db);
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
                    final String listed = viewStatus.get(username);
                    assertThat(listed).as("v_subscription_status of %s", username).isEqualTo(sql[1]);
                    compared++;
                }
                assertThat(compared).isGreaterThan(200);

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

    /** States x expiry offsets around every boundary x grace 0 and 2, plus three forms of a pause. */
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
        for (int i = 0; i < OFFSETS.length; i++) {
            final String offset = OFFSETS[i];
            // The same 2-day pause, seen before its end, after its end, and after it is recorded.
            cases.add(new Case(code + "000001-" + (400 + i), "PAUSED", offset, 2, "1 day", "1 day"));
            cases.add(new Case(code + "000001-" + (500 + i), "PAUSED", offset, 2, "3 days", "-1 day"));
            cases.add(new Case(code + "000001-" + (600 + i), "ACTIVE", offset, "2 days", 2, null, null));
        }
        // Pause ending within the hour: Session-Timeout runs to pause_until.
        cases.add(new Case(code + "000001-700", "PAUSED", "+3 days", 0, "1 day", "30 minutes"));
        return cases;
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
                                              throttle_download_kbps, throttle_upload_kbps)
                values (?, ?, 'Agreement', 10000, 5000, 43200, 100000, 2000, 1000)
                """, pkg, tenant);
        final UUID router = UUID.randomUUID();
        exec(db, "insert into network_router (id, tenant_id, name, tunnel_ip_a) values (?, ?, 'agreement', cast(? as inet))",
                router, tenant, mode == GraceMode.THROTTLED ? "10.254.0.2" : "10.254.0.1");
        try (PreparedStatement insert = db.prepareStatement("""
                insert into subscriptions_subscription
                    (id, tenant_id, customer_id, package_id, router_id, pppoe_username, state, expires_at, grace_days,
                     paused_at, pause_until, terminated_at)
                values (?, ?, ?, ?, ?, ?, ?, now() + cast(? as interval) + cast(? as interval), ?,
                        now() - cast(? as interval), now() + cast(? as interval),
                        case when ? = 'TERMINATED' then now() end)
                """)) {
            for (final Case c : cases) {
                insert.setObject(1, UUID.randomUUID());
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
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    private static Instant dbNow(final Connection db) throws Exception {
        try (ResultSet rs = db.createStatement().executeQuery("select now()")) {
            rs.next();
            return rs.getTimestamp(1).toInstant();
        }
    }

    private static Map<String, AccessPolicy.Facts> readFacts(final Connection db) throws Exception {
        final Map<String, AccessPolicy.Facts> facts = new LinkedHashMap<>();
        try (ResultSet rs = db.createStatement().executeQuery("""
                select pppoe_username, state, expires_at, grace_days, paused_at, pause_until
                from subscriptions_subscription where pppoe_username like 'ZQ%'
                """)) {
            while (rs.next()) {
                facts.put(rs.getString(1), new AccessPolicy.Facts(
                        SubscriptionState.valueOf(rs.getString(2)),
                        instant(rs.getTimestamp(3)),
                        rs.getInt(4),
                        instant(rs.getTimestamp(5)),
                        instant(rs.getTimestamp(6))));
            }
        }
        return facts;
    }

    private static Map<String, Map<String, String>> readView(final Connection db) throws Exception {
        final Map<String, Map<String, String>> view = new HashMap<>();
        try (ResultSet rs = db.createStatement().executeQuery(
                "select username, attribute, value from v_radius_auth_v5 where username like 'ZQ%'")) {
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
                select s.pppoe_username, a.profile, a.display_status, a.next_change_at
                from subscriptions_subscription s join tenancy_tenant t on t.id = s.tenant_id
                cross join lateral subscription_access(s.state, s.expires_at, s.grace_days, s.paused_at, s.pause_until,
                                                       t.grace_mode, ?) a
                where s.pppoe_username like 'ZQ%'
                """)) {
            statement.setTimestamp(1, Timestamp.from(now));
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    final Timestamp next = rs.getTimestamp(4);
                    rows.put(rs.getString(1), new String[] {
                        rs.getString(2), rs.getString(3), next == null ? null : next.toInstant().toString()});
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
