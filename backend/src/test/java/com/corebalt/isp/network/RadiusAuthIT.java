package com.corebalt.isp.network;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.corebalt.isp.network.infrastructure.RadiusPacket;
import com.corebalt.isp.support.FakeNas;
import com.corebalt.isp.support.IntegrationTest;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.utility.MountableFile;

/**
 * The real FreeRADIUS image (radius/) reading {@code radius_client_v3} and {@code v_radius_auth_v6}
 * as {@code isp_radius}, over the real subscription, package, tenant, router and credential tables,
 * loaded with the same fixtures as the developer lab (infra/lab/seed.sql). Logins are sent with radclient inside the
 * container, from 127.0.0.1 (tenant CB's NAS) or 127.0.0.2 (tenant ZZ's NAS).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RadiusAuthIT extends IntegrationTest {

    /** Obvious fakes, used only by this test. */
    static final String LAB_SECRET = "radius-it-fake-secret";

    static final String RADIUS_PASSWORD = "isp_radius_test_only";

    @org.springframework.beans.factory.annotation.Value("${local.server.port}")
    int port;

    /** The lab admin in seed.sql signs in without TOTP (local fixture only; ADR 0010). */
    @Test
    void theSeededLabAdminSignsIn() throws Exception {
        final var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://localhost:" + port + "/api/v1/auth/login"))
                .header("Content-Type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(
                        "{\"tenant\":\"CB\",\"email\":\"lab-admin@corebalt.example.test\",\"password\":\"lab-admin-password\"}"))
                .build();
        final var response = java.net.http.HttpClient.newHttpClient().send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"SIGNED_IN\"").contains("\"accessToken\"");
    }
    static final String CB_NAS = "127.0.0.1";
    static final String ZZ_NAS = "127.0.0.2";

    GenericContainer<?> freeradius;

    @BeforeAll
    void seedAndStartFreeRadius() throws Exception {
        // Build the image first: a cold build takes minutes, and the fixtures' expiry times are
        // relative to the moment they are seeded (S4 assertions depend on that).
        final ImageFromDockerfile image =
                new ImageFromDockerfile("corebalt-isp-radius-it", false)
                        .withFileFromPath(".", Path.of("..", "radius"));
        image.get();

        // Runs after the Spring context, so Flyway has created the tables and views.
        POSTGRES.copyFileToContainer(
                MountableFile.forHostPath(Path.of("..", "infra", "lab", "seed.sql")), "/tmp/seed.sql");
        final ExecResult seeded =
                POSTGRES.execInContainer(
                        "env",
                        "LAB_SECRET=" + LAB_SECRET,
                        "CHR_TUNNEL_IP=10.200.0.11",
                        "psql",
                        "-U",
                        POSTGRES.getUsername(),
                        "-d",
                        "isp",
                        "-q",
                        "-f",
                        "/tmp/seed.sql");
        assertThat(seeded.getExitCode()).as(seeded.getStderr()).isZero();

        final int pgPort = POSTGRES.getMappedPort(5432);
        Testcontainers.exposeHostPorts(pgPort);
        freeradius =
                new GenericContainer<>(image)
                        .withEnv("RADIUS_DB_HOST", "host.testcontainers.internal")
                        .withEnv("RADIUS_DB_PORT", String.valueOf(pgPort))
                        .withEnv("RADIUS_DB_NAME", "isp")
                        .withEnv("RADIUS_DB_USER", "isp_radius")
                        .withEnv("RADIUS_DB_PASSWORD", RADIUS_PASSWORD)
                        .withEnv("RADIUS_LAB_SECRET", LAB_SECRET)
                        // CoA interop below: radclient in the container talks to a fake router on the host.
                        .withExtraHost("host.docker.internal", "host-gateway")
                        .withCommand("freeradius", "-f", "-l", "stdout")
                        .waitingFor(
                                Wait.forLogMessage(".*Ready to process requests.*", 1)
                                        .withStartupTimeout(Duration.ofMinutes(2)));
        freeradius.start();
    }

    @AfterAll
    void stopFreeRadius() {
        if (freeradius != null) {
            freeradius.stop();
        }
    }

    @Test
    void N5_theWorkersCoaCodecAgreesWithRadclient() throws Exception {
        try (FakeNas nas = new FakeNas("0.0.0.0", 0, LAB_SECRET)) {
            final ExecResult coa = dynamicAuthorization(nas, "coa",
                    "User-Name = CB000001\nAcct-Session-Id = 81c00001\nMikrotik-Rate-Limit = \"2M/4M\"\n");
            assertThat(coa.getStdout()).as(coa.getStderr()).contains("Received CoA-ACK");
            assertThat(nas.received()).singleElement().satisfies(r -> {
                assertThat(r.signedWithSecret()).isTrue();
                assertThat(r.packet().code()).isEqualTo(RadiusPacket.COA_REQUEST);
                assertThat(r.packet().string(RadiusPacket.USER_NAME)).contains("CB000001");
                assertThat(r.packet().string(RadiusPacket.ACCT_SESSION_ID)).contains("81c00001");
                assertThat(r.packet().vendorString(RadiusPacket.MIKROTIK_VENDOR, RadiusPacket.MIKROTIK_RATE_LIMIT))
                        .contains("2M/4M");
            });

            nas.mode(FakeNas.Mode.SESSION_GONE);
            final ExecResult disconnect = dynamicAuthorization(nas, "disconnect",
                    "User-Name = CB000001\nAcct-Session-Id = 81c00001\n");
            assertThat(disconnect.getStdout()).as(disconnect.getStderr())
                    .contains("Received Disconnect-NAK")
                    .contains("Session-Context-Not-Found");
        }
    }

    /** radclient as the sender, the worker's codec in the fake router: both directions must verify. */
    private ExecResult dynamicAuthorization(final FakeNas nas, final String type, final String attributes) throws Exception {
        return freeradius.execInContainer("sh", "-c",
                "printf \"$1\" | radclient -x -r 2 -t 3 \"host.docker.internal:$2\" \"$3\" \"$RADIUS_LAB_SECRET\"",
                "sh", attributes, String.valueOf(nas.port()), type);
    }

    @Test
    void S2_activeLineBeforeExpiryIsFull() throws Exception {
        final RadiusReply reply = login("CB000001", CB_NAS);
        assertThat(reply.code()).isEqualTo("Access-Accept");
        assertThat(reply.attributes())
                .containsEntry("Mikrotik-Rate-Limit", "5M/10M")
                .containsEntry("Framed-Pool", "full")
                .doesNotContainKey("Mikrotik-Address-List");
    }

    @Test
    void S2_insideGraceWithThrottledModeIsThrottled() throws Exception {
        final RadiusReply reply = login("CB000002", CB_NAS);
        assertThat(reply.code()).isEqualTo("Access-Accept");
        assertThat(reply.attributes())
                .containsEntry("Mikrotik-Rate-Limit", "1M/2M")
                .containsEntry("Framed-Pool", "full")
                .containsEntry("Mikrotik-Address-List", "throttled");
    }

    @Test
    void S2_insideGraceWithFullSpeedModeIsFull() throws Exception {
        final RadiusReply reply = login("ZZ000002", ZZ_NAS);
        assertThat(reply.code()).isEqualTo("Access-Accept");
        assertThat(reply.attributes())
                .containsEntry("Mikrotik-Rate-Limit", "5M/10M")
                .containsEntry("Framed-Pool", "full")
                .doesNotContainKey("Mikrotik-Address-List");
    }

    @Test
    void staticIpIsReturnedInsteadOfAPool() throws Exception {
        final RadiusReply reply = login("CB000003", CB_NAS);
        assertThat(reply.code()).isEqualTo("Access-Accept");
        assertThat(reply.attributes())
                .containsEntry("Framed-IP-Address", "10.10.0.53")
                .doesNotContainKey("Framed-Pool");
    }

    @Test
    void T4_lineWithRevokedPasswordCannotLogIn() throws Exception {
        setLinePassword("CB000003", null);
        try {
            assertThat(login("CB000003", CB_NAS).code()).isEqualTo("Access-Reject");
        } finally {
            setLinePassword("CB000003", "lab-password");
        }
        assertThat(login("CB000003", CB_NAS).code()).isEqualTo("Access-Accept");
    }

    @Test
    void N3_pastGraceIsAcceptedIntoRestricted() throws Exception {
        final RadiusReply reply = login("CB000004", CB_NAS);
        assertThat(reply.code()).isEqualTo("Access-Accept");
        assertThat(reply.attributes())
                .containsEntry("Mikrotik-Rate-Limit", "512k/512k")
                .containsEntry("Framed-Pool", "restricted")
                .containsEntry("Mikrotik-Address-List", "restricted")
                .containsEntry("Session-Timeout", "86400");
    }

    @Test
    void S7_pausedLineIsRestrictedNotRejected() throws Exception {
        final RadiusReply reply = login("CB000005", CB_NAS);
        assertThat(reply.code()).isEqualTo("Access-Accept");
        assertThat(reply.attributes()).containsEntry("Framed-Pool", "restricted");
    }

    @Test
    void S7_pauseEndsAtPauseUntilWithoutAnyJob() throws Exception {
        // ZZ000003: paused for 10 days, pause_until an hour ago, expiry 2 days ago before the shift.
        final RadiusReply reply = login("ZZ000003", ZZ_NAS);
        assertThat(reply.code()).isEqualTo("Access-Accept");
        assertThat(reply.attributes())
                .containsEntry("Mikrotik-Rate-Limit", "5M/10M")
                .containsEntry("Framed-Pool", "full")
                .doesNotContainKey("Mikrotik-Address-List");
    }

    @Test
    void N3_suspendedAndPendingAreRejectedWithReason() throws Exception {
        assertThat(login("CB000006", CB_NAS))
                .isEqualTo(new RadiusReply("Access-Reject", Map.of("Reply-Message", "Account suspended")));
        assertThat(login("CB000007", CB_NAS))
                .isEqualTo(new RadiusReply("Access-Reject", Map.of("Reply-Message", "Account not yet active")));
    }

    @Test
    void S4_sessionTimeoutIsSecondsToNextProfileChange() throws Exception {
        // Expires in 2 hours with no grace: the next change is expires_at.
        final long timeout = Long.parseLong(login("CB000008", CB_NAS).attributes().get("Session-Timeout"));
        assertThat(timeout).isBetween(6_900L, 7_200L);
    }

    @Test
    void S4_sessionTimeoutIsCappedAtOneDay() throws Exception {
        assertThat(login("CB000001", CB_NAS).attributes()).containsEntry("Session-Timeout", "86400");
        // Grace ends in about 71 hours: still capped.
        assertThat(login("CB000002", CB_NAS).attributes()).containsEntry("Session-Timeout", "86400");
    }

    @Test
    void T3_customerCannotLogInThroughAnotherTenantsRouter() throws Exception {
        assertThat(login("ZZ000001", CB_NAS))
                .isEqualTo(new RadiusReply("Access-Reject", Map.of("Reply-Message", "Unknown user")));
        assertThat(login("CB000001", ZZ_NAS).code()).isEqualTo("Access-Reject");
    }

    @Test
    void T3_customerLogsInThroughOwnTenantsRouter() throws Exception {
        assertThat(login("ZZ000001", ZZ_NAS).code()).isEqualTo("Access-Accept");
    }

    @Test
    void wrongPasswordIsRejected() throws Exception {
        assertThat(login("CB000001", "not-the-password", CB_NAS).code()).isEqualTo("Access-Reject");
    }

    @Test
    void N2_radiusRoleReadsOnlyTheViews() throws SQLException {
        try (Connection radius =
                DriverManager.getConnection(POSTGRES.getJdbcUrl(), "isp_radius", RADIUS_PASSWORD)) {
            try (var rs = radius.createStatement().executeQuery("select count(*) from v_radius_auth_v6")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getLong(1)).isPositive();
            }
            assertThatThrownBy(() -> radius.createStatement().executeQuery("select * from network_radius_credential"))
                    .hasMessageContaining("permission denied");
            assertThatThrownBy(() -> radius.createStatement().executeQuery("select * from subscriptions_subscription"))
                    .hasMessageContaining("permission denied");
            assertThatThrownBy(() -> radius.createStatement().executeQuery("select * from tenancy_tenant"))
                    .hasMessageContaining("permission denied");
        }
    }

    @Test
    void N7_startInterimStopIsOneSessionWithGigawordsFoldedIn() throws Exception {
        final String session = "it-" + System.nanoTime();
        assertThat(accounting(CB_NAS, "Start", session, "CB000001", "")).isEqualTo("Accounting-Response");
        assertThat(accounting(CB_NAS, "Start", session, "CB000001", "")).as("retransmitted").isEqualTo("Accounting-Response");
        assertThat(accounting(CB_NAS, "Interim-Update", session, "CB000001",
                "Acct-Session-Time = 300\nAcct-Input-Octets = 500\nAcct-Input-Gigawords = 1\nAcct-Output-Octets = 7000\n"
                        + "Framed-IP-Address = 100.64.0.9\n")).isEqualTo("Accounting-Response");
        assertThat(accounting(CB_NAS, "Stop", session, "CB000001",
                "Acct-Session-Time = 600\nAcct-Input-Octets = 900\nAcct-Input-Gigawords = 1\nAcct-Output-Octets = 9000\n"
                        + "Acct-Terminate-Cause = User-Request\n")).isEqualTo("Accounting-Response");

        final Map<String, Object> row = OwnerSqlRow.one("""
                select count(*) over () as n, a.acctinputoctets, a.acctoutputoctets, a.acctterminatecause, a.acctstoptime is not null as stopped,
                       t.code, s.pppoe_username, host(a.nasipaddress) as nas
                from radacct a join tenancy_tenant t on t.id = a.tenant_id
                left join subscriptions_subscription s on s.id = a.subscription_id
                where a.acctsessionid = ?""", session);
        assertThat(row).containsEntry("n", 1L).containsEntry("acctinputoctets", (1L << 32) + 900)
                .containsEntry("acctoutputoctets", 9000L).containsEntry("acctterminatecause", "User-Request")
                .containsEntry("stopped", true).containsEntry("code", "CB").containsEntry("pppoe_username", "CB000001")
                .containsEntry("nas", CB_NAS);
    }

    @Test
    void N7_anInterimWithoutAStartStillOpensTheSession() throws Exception {
        final String session = "it-" + System.nanoTime();
        accounting(CB_NAS, "Interim-Update", session, "CB000002", "Acct-Session-Time = 120\nAcct-Output-Octets = 42\n");
        final Map<String, Object> row = OwnerSqlRow.one(
                "select acctoutputoctets, acctstoptime is null as open, extract(epoch from acctupdatetime - acctstarttime)::int as age from radacct where acctsessionid = ?",
                session);
        assertThat(row).containsEntry("acctoutputoctets", 42L).containsEntry("open", true).containsEntry("age", 120);
    }

    @Test
    void T3_theNasIsTheSendingRouterNotTheClaimedAddress() throws Exception {
        final String session = "it-" + System.nanoTime();
        accounting(ZZ_NAS, "Start", session, "ZZ000001", "NAS-IP-Address = 127.0.0.1\n");
        assertThat(OwnerSqlRow.one("select t.code, host(a.nasipaddress) as nas from radacct a join tenancy_tenant t on t.id = a.tenant_id "
                + "where a.acctsessionid = ?", session)).containsEntry("code", "ZZ").containsEntry("nas", ZZ_NAS);
    }

    @Test
    void accountingOnClosesOnlyThatRoutersOpenSessions() throws Exception {
        final String cb = "it-" + System.nanoTime();
        final String zz = "it-" + System.nanoTime() + "z";
        accounting(CB_NAS, "Start", cb, "CB000001", "");
        accounting(ZZ_NAS, "Start", zz, "ZZ000001", "");
        assertThat(accounting(ZZ_NAS, "Accounting-On", "it-on-" + System.nanoTime(), "", "")).isEqualTo("Accounting-Response");
        assertThat(OwnerSqlRow.one("select acctterminatecause from radacct where acctsessionid = ?", zz))
                .containsEntry("acctterminatecause", "NAS-Reboot");
        assertThat(OwnerSqlRow.one("select acctstoptime is null as open from radacct where acctsessionid = ?", cb))
                .containsEntry("open", true);
    }

    @Test
    void N2_radiusRoleWritesAccountingButCannotReadIt() throws SQLException {
        try (Connection radius =
                DriverManager.getConnection(POSTGRES.getJdbcUrl(), "isp_radius", RADIUS_PASSWORD)) {
            assertThatThrownBy(() -> radius.createStatement().executeQuery("select username, acctinputoctets from radacct"))
                    .hasMessageContaining("permission denied");
            assertThatThrownBy(() -> radius.createStatement().executeQuery("select * from network_usage_daily"))
                    .hasMessageContaining("permission denied");
        }
    }

    /** One accounting request from {@code nas}; returns the response code. */
    private String accounting(final String nas, final String status, final String session, final String username,
                              final String extra) throws Exception {
        final String attributes = (username.isEmpty() ? "" : "User-Name = " + username + "\n")
                + "Acct-Status-Type = " + status + "\nAcct-Session-Id = " + session + "\nNAS-Port = 15\n"
                + "Event-Timestamp = " + (System.currentTimeMillis() / 1000) + "\n" + extra
                + "Packet-Src-IP-Address = " + nas + "\n";
        final ExecResult result = freeradius.execInContainer("sh", "-c",
                "printf '%s' \"$1\" | radclient -x -r 3 -t 3 127.0.0.1:1813 acct \"$RADIUS_LAB_SECRET\"", "sh", attributes);
        for (final String line : result.getStdout().split("\\R")) {
            if (line.startsWith("Received ")) {
                return line.split(" ")[1];
            }
        }
        throw new AssertionError("no accounting response: " + result.getStdout() + result.getStderr());
    }

    /** One row as the owner (outside RLS), with JDBC types. */
    static final class OwnerSqlRow {
        static Map<String, Object> one(final String sql, final Object... args) throws SQLException {
            try (Connection owner = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                    var statement = owner.prepareStatement(sql)) {
                for (int i = 0; i < args.length; i++) {
                    statement.setObject(i + 1, args[i]);
                }
                try (var rs = statement.executeQuery()) {
                    assertThat(rs.next()).as("no row for %s", args).isTrue();
                    final Map<String, Object> row = new LinkedHashMap<>();
                    for (int c = 1; c <= rs.getMetaData().getColumnCount(); c++) {
                        row.put(rs.getMetaData().getColumnLabel(c), rs.getObject(c));
                    }
                    return row;
                }
            }
        }
    }

    /** As the owner, like the lab scripts: the application role cannot read or null a secret this way. */
    private void setLinePassword(final String username, final String password) throws SQLException {
        try (Connection owner =
                        DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var update =
                        owner.prepareStatement(
                                """
                                update network_radius_credential k set secret = ?
                                from subscriptions_subscription s
                                where k.subscription_id = s.id and s.pppoe_username = ?
                                """)) {
            update.setString(1, password);
            update.setString(2, username);
            assertThat(update.executeUpdate()).isOne();
        }
    }

    private RadiusReply login(final String username, final String nas) throws Exception {
        return login(username, "lab-password", nas);
    }

    /** Sends one PAP Access-Request from {@code nas} and returns the response code and attributes. */
    private RadiusReply login(final String username, final String password, final String nas) throws Exception {
        final ExecResult result =
                freeradius.execInContainer(
                        "sh",
                        "-c",
                        "printf 'User-Name = %s\\nUser-Password = %s\\nPacket-Src-IP-Address = %s\\n' \"$1\" \"$2\" \"$3\""
                                + " | radclient -x -r 3 -t 3 127.0.0.1:1812 auth \"$RADIUS_LAB_SECRET\"",
                        "sh",
                        username,
                        password,
                        nas);
        String code = null;
        final Map<String, String> attributes = new LinkedHashMap<>();
        for (final String line : result.getStdout().split("\\R")) {
            if (line.startsWith("Received ")) {
                code = line.split(" ")[1];
            } else if (code != null && line.startsWith("\t") && line.contains(" = ")) {
                final String[] pair = line.strip().split(" = ", 2);
                if (!pair[0].equals("Message-Authenticator")) {
                    attributes.put(pair[0], pair[1].replaceAll("^\"|\"$", ""));
                }
            }
        }
        assertThat(code).as("no RADIUS response: %s %s", result.getStdout(), result.getStderr()).isNotNull();
        return new RadiusReply(code, attributes);
    }

    record RadiusReply(String code, Map<String, String> attributes) {}
}
