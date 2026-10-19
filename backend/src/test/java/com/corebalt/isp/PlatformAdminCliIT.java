package com.corebalt.isp;

import static org.assertj.core.api.Assertions.assertThat;

import com.corebalt.isp.identity.application.PlatformAdmins;
import com.corebalt.isp.support.IntegrationTest;
import com.corebalt.isp.support.OwnerSql;
import com.corebalt.isp.support.TestTokens;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;

/**
 * The break-glass command exactly as the container runs it (ADR 0010): a second application context
 * without a web server, against the same database. It must start, reset the admin, print the
 * temporary password and exit 0; an unknown email exits 1.
 */
class PlatformAdminCliIT extends IntegrationTest {

    private static final String EMAIL = "cli-root@corebalt.example.test";

    @Value("${local.server.port}")
    int port;

    @Autowired PlatformAdmins admins;
    @Autowired Environment environment;

    @Test
    void breakGlassCommandResetsTheAdminAndExitsZero() throws Exception {
        OwnerSql.client().sql("delete from identity_impersonation").update();
        OwnerSql.client().sql("delete from identity_platform_recovery_code").update();
        OwnerSql.client().sql("delete from identity_refresh_token where subject_type = 'PLATFORM'").update();
        OwnerSql.client().sql("delete from identity_platform_admin").update();
        admins.bootstrap(EMAIL, "bootstrap-password-fake");

        final Run reset = run("reset-platform-admin", EMAIL);
        assertThat(reset.exitCode()).as(reset.err()).isZero();
        final Matcher printed = Pattern.compile("\\n {4}(\\S{16})\\n").matcher(reset.out());
        assertThat(printed.find()).as(reset.out()).isTrue();

        final HttpResponse<String> login = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/platform/auth/login"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"email\":\"" + EMAIL + "\",\"password\":\"" + printed.group(1) + "\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(login.body()).contains("\"status\":\"CHANGE_PASSWORD\"");
        assertThat(OwnerSql.client().sql("select count(*) from identity_platform_audit_log where action = 'platform.admin_break_glass_reset'")
                .query(Long.class).single()).isPositive();

        assertThat(run("reset-platform-admin", "nobody@corebalt.example.test").exitCode()).isEqualTo(1);
        assertThat(run("reset-platform-admin").exitCode()).isEqualTo(2);
    }

    record Run(int exitCode, String out, String err) {}

    private Run run(final String... command) {
        final String[] args = new String[command.length + 14];
        System.arraycopy(command, 0, args, 0, command.length);
        int i = command.length;
        args[i++] = "--spring.profiles.active=test";
        args[i++] = "--spring.datasource.url=" + POSTGRES.getJdbcUrl();
        args[i++] = "--spring.datasource.username=isp_app";
        args[i++] = "--spring.datasource.password=" + environment.getProperty("spring.datasource.password");
        args[i++] = "--spring.flyway.url=" + POSTGRES.getJdbcUrl();
        args[i++] = "--spring.flyway.user=" + POSTGRES.getUsername();
        args[i++] = "--spring.flyway.password=" + POSTGRES.getPassword();
        args[i++] = "--spring.rabbitmq.host=" + environment.getProperty("spring.rabbitmq.host");
        args[i++] = "--spring.rabbitmq.port=" + environment.getProperty("spring.rabbitmq.port");
        args[i++] = "--spring.data.redis.host=" + environment.getProperty("spring.data.redis.host");
        args[i++] = "--spring.data.redis.port=" + environment.getProperty("spring.data.redis.port");
        args[i++] = "--isp.auth.jwt.kid=" + TestTokens.KID;
        args[i++] = "--isp.auth.jwt.private-key=" + TestTokens.privateKeyBase64();
        args[i++] = "--isp.secrets.master-key=" + environment.getProperty("isp.secrets.master-key");
        final PrintStream out = System.out;
        final PrintStream err = System.err;
        final ByteArrayOutputStream captured = new ByteArrayOutputStream();
        final ByteArrayOutputStream capturedErr = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(capturedErr, true, StandardCharsets.UTF_8));
            final int code = IspApplication.resetPlatformAdmin(command.length < 2 ? command : args);
            return new Run(code, captured.toString(StandardCharsets.UTF_8).replace("\r\n", "\n"),
                    capturedErr.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(out);
            System.setErr(err);
        }
    }
}
