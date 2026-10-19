package com.corebalt.isp.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.corebalt.isp.support.IntegrationTest;
import com.corebalt.isp.support.TestStaff;
import com.corebalt.isp.support.TestTenants;
import com.corebalt.isp.support.TestTokens;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The HTTP boundary (ADR 0010): only the platform's own RS256 tokens are accepted, the token type
 * decides the API, the tenant comes only from the token (T1), and errors are RFC 9457 problems.
 */
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
        assertThat(response.body()).contains("/api/v1/branding").contains("/api/v1/auth/login");
    }

    @Test
    void jwksPublishesOnlyThePublicKey() throws Exception {
        final HttpResponse<String> response = get("/.well-known/jwks.json", null);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"kid\":\"" + TestTokens.KID + "\"").contains("\"kty\":\"RSA\"")
                .doesNotContain("\"d\":").doesNotContain("\"p\":");
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
    void unsignedTokenIs401() throws Exception {
        final UUID tenant = TestTenants.create(jdbc, tx);
        final Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        final String none = encoder.encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8)) + "."
                + encoder.encodeToString(("{\"sub\":\"" + TestStaff.create(tenant) + "\",\"typ\":\"STAFF\",\"tenant_id\":\""
                + tenant + "\",\"ver\":0,\"iss\":\"corebalt-isp\",\"aud\":\"corebalt-isp-api\"}").getBytes(StandardCharsets.UTF_8)) + ".";
        assertThat(get("/api/v1/branding", none).statusCode()).isEqualTo(401);
    }

    @Test
    void tokenSignedWithAnotherKeyIs401() throws Exception {
        final UUID tenant = TestTenants.create(jdbc, tx);
        final String forged = TestTokens.signWithForeignKey(Map.of("sub", TestStaff.create(tenant).toString(),
                Tokens.TYPE, Tokens.STAFF, Tokens.TENANT, tenant.toString(), Tokens.VERSION, 0));
        assertThat(get("/api/v1/branding", forged).statusCode()).isEqualTo(401);
    }

    @Test
    void wrongAudienceIs401() throws Exception {
        final UUID tenant = TestTenants.create(jdbc, tx);
        final String token = TestTokens.sign(Map.of("sub", TestStaff.create(tenant).toString(), Tokens.TYPE, Tokens.STAFF,
                Tokens.TENANT, tenant.toString(), Tokens.VERSION, 0, "aud", "someone-else"));
        assertThat(get("/api/v1/branding", token).statusCode()).isEqualTo(401);
    }

    @Test
    void T1_staffTokenWithoutTenantClaimIs403ProblemDetail() throws Exception {
        final HttpResponse<String> response = get("/api/v1/branding",
                TestTokens.sign(Map.of(Tokens.TYPE, Tokens.STAFF, Tokens.VERSION, 0)));
        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                type -> assertThat(type).startsWith(PROBLEM_JSON));
        assertThat(response.body())
                .contains("https://problems.corebalt.app/tenant-context-missing")
                .contains("correlationId");
    }

    @Test
    void T1_tokenWithMalformedTenantClaimIs403() throws Exception {
        final HttpResponse<String> response = get("/api/v1/branding",
                TestTokens.sign(Map.of(Tokens.TYPE, Tokens.STAFF, Tokens.TENANT, "not-a-uuid", Tokens.VERSION, 0)));
        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body()).contains("tenant-context-missing");
    }

    @Test
    void T1_tenantClaimScopesTheRequest() throws Exception {
        final UUID tenant = TestTenants.create(jdbc, tx);
        final HttpResponse<String> response = get("/api/v1/branding", TestTokens.staff(TestStaff.create(tenant), tenant));
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"name\":\"Tenant ").contains("\"primaryColor\":\"#0f766e\"");
    }

    @Test
    void T1_aTenantHeaderOrParameterIsIgnored() throws Exception {
        final UUID mine = TestTenants.create(jdbc, tx);
        final UUID other = TestTenants.create(jdbc, tx);
        final HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/branding?tenant_id=" + other))
                .header("Authorization", "Bearer " + TestTokens.staff(TestStaff.create(mine), mine))
                .header("X-Tenant-Id", other.toString())
                .build();
        final HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        final String myName = jdbc.sql("select name from tenancy_tenant where id = :id").param("id", mine).query(String.class).single();
        assertThat(response.body()).contains("\"name\":\"" + myName + "\"");
    }

    @Test
    void expiredTokenIs401() throws Exception {
        final UUID tenant = TestTenants.create(jdbc, tx);
        final long past = Instant.now().minusSeconds(120).getEpochSecond();
        final String token = TestTokens.sign(Map.of("sub", TestStaff.create(tenant).toString(), Tokens.TYPE, Tokens.STAFF,
                Tokens.TENANT, tenant.toString(), Tokens.VERSION, 0, "exp", past));
        assertThat(get("/api/v1/branding", token).statusCode()).isEqualTo(401);
    }

    @Test
    void tokenTypeDecidesTheApi() throws Exception {
        final UUID tenant = TestTenants.create(jdbc, tx);
        final String staff = TestTokens.staff(TestStaff.create(tenant), tenant);
        final String customer = TestTokens.sign(Map.of(Tokens.TYPE, Tokens.CUSTOMER, Tokens.TENANT, tenant.toString(), Tokens.VERSION, 0));
        final String challenge = TestTokens.sign(Map.of(Tokens.TYPE, Tokens.CHALLENGE, Tokens.TENANT, tenant.toString(),
                "purpose", "TOTP", "sty", "STAFF"));
        assertThat(get("/api/platform/tenants", staff).statusCode()).isEqualTo(403);
        assertThat(get("/api/v1/branding", customer).statusCode()).isEqualTo(403);
        assertThat(get("/api/v1/branding", challenge).statusCode()).isEqualTo(403);
    }

    @Test
    void A2_permissionCodesComeOnlyFromTheTokenAndUnknownCodesAreIgnored() throws Exception {
        final UUID tenant = TestTenants.create(jdbc, tx);
        final String token = TestTokens.sign(Map.of("sub", TestStaff.create(tenant).toString(), Tokens.TYPE, Tokens.STAFF,
                Tokens.TENANT, tenant.toString(), Tokens.VERSION, 0, Tokens.PERMISSIONS, List.of("ROLE_ADMIN", "superuser")));
        assertThat(get("/api/v1/staff", token).statusCode()).isEqualTo(403);
        assertThat(get("/api/v1/branding", token).statusCode()).isEqualTo(200);
    }

    @Test
    void aTokenOlderThanTheUsersTokenVersionIs401() throws Exception {
        final UUID tenant = TestTenants.create(jdbc, tx);
        final UUID staff = TestStaff.create(tenant);
        final String token = TestTokens.staff(staff, tenant);
        assertThat(get("/api/v1/branding", token).statusCode()).isEqualTo(200);
        com.corebalt.isp.support.OwnerSql.client().sql("update identity_staff_user set token_version = 1 where id = :id")
                .param("id", staff).update();
        // The cached version expires within 10 minutes; services bump through TokenVersions, which clears it.
        flushRedis();
        final HttpResponse<String> refused = get("/api/v1/branding", token);
        assertThat(refused.statusCode()).isEqualTo(401);
        assertThat(refused.body()).contains("This session has ended");
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

    @Autowired org.springframework.data.redis.core.StringRedisTemplate redis;

    private void flushRedis() {
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
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
}
