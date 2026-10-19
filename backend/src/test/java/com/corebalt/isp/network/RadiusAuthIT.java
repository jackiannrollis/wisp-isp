package com.corebalt.isp.network;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
 * The step-2 network lab under test: the real FreeRADIUS image (radius/) reading
 * {@code radius_client_v2} and {@code v_radius_auth_v2} as {@code isp_radius}, loaded with the same
 * fixtures as the developer lab (infra/lab/seed.sql). Logins are sent with radclient inside the
 * container, from 127.0.0.1 (tenant CB's NAS) or 127.0.0.2 (tenant ZZ's NAS).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RadiusAuthIT extends IntegrationTest {

    /** Obvious fakes, used only by this test. */
    static final String LAB_SECRET = "radius-it-fake-secret";

    static final String RADIUS_PASSWORD = "isp_radius_test_only";
    static final String CB_NAS = "127.0.0.1";
    static final String ZZ_NAS = "127.0.0.2";

    GenericContainer<?> freeradius;

    @BeforeAll
    void seedAndStartFreeRadius() throws Exception {
        // Runs after the Spring context, so Flyway has created the lab tables and v2 views.
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
                new GenericContainer<>(
                                new ImageFromDockerfile("corebalt-isp-radius-it", false)
                                        .withFileFromPath(".", Path.of("..", "radius")))
                        .withEnv("RADIUS_DB_HOST", "host.testcontainers.internal")
                        .withEnv("RADIUS_DB_PORT", String.valueOf(pgPort))
                        .withEnv("RADIUS_DB_NAME", "isp")
                        .withEnv("RADIUS_DB_USER", "isp_radius")
                        .withEnv("RADIUS_DB_PASSWORD", RADIUS_PASSWORD)
                        .withEnv("RADIUS_LAB_SECRET", LAB_SECRET)
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
        final RadiusReply reply = login("CB000003", CB_NAS);
        assertThat(reply.code()).isEqualTo("Access-Accept");
        assertThat(reply.attributes())
                .containsEntry("Mikrotik-Rate-Limit", "5M/10M")
                .doesNotContainKey("Mikrotik-Address-List");
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
            try (var rs = radius.createStatement().executeQuery("select count(*) from v_radius_auth_v2")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getLong(1)).isPositive();
            }
            assertThatThrownBy(() -> radius.createStatement().executeQuery("select * from network_lab_line"))
                    .hasMessageContaining("permission denied");
            assertThatThrownBy(() -> radius.createStatement().executeQuery("select * from tenancy_tenant"))
                    .hasMessageContaining("permission denied");
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
                                + " | radclient -x -r 1 -t 3 127.0.0.1:1812 auth \"$RADIUS_LAB_SECRET\"",
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
