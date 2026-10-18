package com.corebalt.isp.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.corebalt.isp.support.IntegrationTest;
import com.corebalt.isp.support.TestTenants;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/** The HTTP boundary: authentication, tenant resolution (T1) and RFC 9457 problem details. */
class ApiSecurityIT extends IntegrationTest {

    private static final String PROBLEM_JSON = "application/problem+json";

    @Value("${local.server.port}")
    int port;

    @Autowired JdbcClient jdbc;
    @Autowired TransactionTemplate tx;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void healthIsPublic() throws Exception {
        final HttpResponse<String> response = get("/actuator/health", null);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"UP\"");
    }

    @Test
    void openApiDocsArePublic() throws Exception {
        final HttpResponse<String> response = get("/api/docs", null);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("/api/v1/branding");
    }

    @Test
    void apiWithoutTokenIs401ProblemDetail() throws Exception {
        final HttpResponse<String> response = get("/api/v1/branding", null);
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                type -> assertThat(type).startsWith(PROBLEM_JSON));
        assertThat(response.body()).contains("https://problems.corebalt.app/unauthenticated");
        assertThat(response.headers().firstValue("X-Correlation-Id")).isPresent();
    }

    @Test
    void T1_tokenWithoutTenantClaimIs403ProblemDetail() throws Exception {
        final HttpResponse<String> response =
                get("/api/v1/branding", token("{\"sub\":\"staff-1\"}"));
        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                type -> assertThat(type).startsWith(PROBLEM_JSON));
        assertThat(response.body())
                .contains("https://problems.corebalt.app/tenant-context-missing")
                .contains("correlationId");
    }

    @Test
    void T1_tokenWithMalformedTenantClaimIs403() throws Exception {
        final HttpResponse<String> response =
                get("/api/v1/branding", token("{\"sub\":\"staff-1\",\"tenant_id\":\"not-a-uuid\"}"));
        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body()).contains("tenant-context-missing");
    }

    @Test
    void T1_tenantClaimScopesTheRequest() throws Exception {
        final UUID tenant = TestTenants.create(jdbc, tx);
        final HttpResponse<String> response =
                get(
                        "/api/v1/branding",
                        token("{\"sub\":\"staff-1\",\"tenant_id\":\"" + tenant + "\"}"));
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"name\":\"Tenant ").contains("\"primaryColor\":\"#0f766e\"");
    }

    @Test
    void expiredTokenIs401() throws Exception {
        final long past = Instant.now().minusSeconds(60).getEpochSecond();
        final HttpResponse<String> response =
                get(
                        "/api/v1/branding",
                        token("{\"sub\":\"staff-1\",\"tenant_id\":\"" + UUID.randomUUID() + "\",\"exp\":" + past + "}"));
        assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test
    void unknownPathsAreDenied() throws Exception {
        assertThat(get("/internal/anything", null).statusCode()).isIn(401, 403);
    }

    @Test
    void correlationIdIsEchoed() throws Exception {
        final HttpRequest request =
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/health"))
                        .header("X-Correlation-Id", "abc-123")
                        .build();
        final HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.headers().firstValue("X-Correlation-Id")).hasValue("abc-123");
    }

    private HttpResponse<String> get(final String path, final String bearer)
            throws IOException, InterruptedException {
        final HttpRequest.Builder request =
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** Unsigned JWT ({@code alg: none}); accepted only by the local/test stub decoder. */
    private static String token(final String claimsJson) {
        final Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        return encoder.encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8))
                + "."
                + encoder.encodeToString(claimsJson.getBytes(StandardCharsets.UTF_8))
                + ".";
    }
}
