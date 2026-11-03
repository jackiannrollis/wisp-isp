package com.corebalt.isp.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.corebalt.isp.shared.security.Tokens;
import com.corebalt.isp.support.Api;
import com.corebalt.isp.support.Catalog;
import com.corebalt.isp.support.IntegrationTest;
import com.corebalt.isp.support.OwnerSql;
import com.corebalt.isp.support.TestTokens;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The impersonation hand-off (ADR 0017, Q17): the console's start returns a single-use code (60 seconds), never a
 * token; the admin app exchanges it once for a token without refresh token or cookie; money, secrets, the
 * account's policy, staff and paid_until imports are refused while impersonating even an Owner; the impersonated
 * session can end itself.
 */
class ImpersonationHandoffIT extends IntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final AtomicInteger CODES = new AtomicInteger();

    @Value("${local.server.port}")
    int port;

    Api platform;
    UUID tenantId;
    UUID ownerId;

    @BeforeEach
    void tenantWithAnActiveOwner() {
        final UUID adminId = UUID.randomUUID();
        OwnerSql.client().sql("""
                        insert into identity_platform_admin (id, email, display_name, password_hash, must_change_password)
                        values (:id, :email, 'Corebalt Support Test', 'not-a-hash', false)
                        """)
                .param("id", adminId).param("email", "support-" + adminId + "@corebalt.example.test").update();
        platform = new Api(port, TestTokens.sign(Map.of("sub", adminId.toString(), Tokens.TYPE, Tokens.PLATFORM, Tokens.VERSION, 0)));
        final int n = CODES.incrementAndGet();
        final String code = "H" + (char) ('A' + n % 26) + (char) ('A' + (System.nanoTime() / 7) % 26) + (char) ('A' + (n / 26) % 26);
        final JsonNode created = platform.post("/api/platform/tenants", """
                {"code":"%s","name":"Handoff ISP %s","subdomain":"handoff-%s","ownerEmail":"owner-%s@handoff.example.test",
                 "ownerName":"Wanjiru Owner","ownerPhone":"+254700000123"}
                """.formatted(code, code, code.toLowerCase(), code.toLowerCase())).json();
        tenantId = UUID.fromString(created.get("tenant").get("id").asString());
        ownerId = UUID.fromString(created.get("owner").get("staff").get("id").asString());
        // The Owner has signed in before (a fixture: impersonation needs an ACTIVE member).
        OwnerSql.client().sql("update identity_staff_user set status = 'ACTIVE', must_change_password = false where id = :id")
                .param("id", ownerId).update();
    }

    @Test
    void ADR0017_startGivesASingleUseCodeNotAToken_theExchangeGivesATokenWithoutCookieOnce() throws Exception {
        final JsonNode started = start();
        assertThat(started.has("accessToken")).isFalse();
        final String code = started.get("code").asString();
        assertThat(code).hasSizeGreaterThanOrEqualTo(40);
        assertThat(started.get("openUrl").asString()).endsWith("/impersonate#code=" + code);
        assertThat(OwnerSql.client().sql("select handoff_code_hash from identity_impersonation where id = :id")
                .param("id", UUID.fromString(started.get("id").asString())).query(String.class).single())
                .isNotEqualTo(code).hasSize(64);

        final HttpResponse<String> exchanged = exchange(code);
        assertThat(exchanged.statusCode()).as(exchanged.body()).isEqualTo(200);
        assertThat(exchanged.headers().firstValue("Set-Cookie")).isEmpty();
        assertThat(exchanged.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        final JsonNode body = JSON.readTree(exchanged.body());
        assertThat(body.get("staffName").asString()).isEqualTo("Wanjiru Owner");
        assertThat(body.get("platformAdminName").asString()).isEqualTo("Corebalt Support Test");

        final JsonNode claims = claims(body.get("accessToken").asString());
        assertThat(claims.get(Tokens.ACTOR).get("sub").asString()).isNotBlank();
        assertThat(claims.get("imp").asString()).isEqualTo(started.get("id").asString());
        final List<String> codes = claims.get("permissions").valueStream().map(JsonNode::asString).toList();
        assertThat(codes).contains("customers.write", "subscriptions.write", "network.write", "payments.assign")
                .doesNotContain("payments.record_manual", "billing.adjust", "settings.secrets", "settings.write", "staff.manage",
                        "subscriptions.import_active");
        final Api acting = new Api(port, body.get("accessToken").asString());
        final JsonNode me = acting.get("/api/v1/me").json();
        assertThat(me.get("impersonatedBy").asString()).isNotBlank();
        assertThat(me.get("permissions").toString()).doesNotContain("billing.adjust").contains("customers.write");

        assertThat(exchange(code).statusCode()).as("a code is used once").isEqualTo(401);
    }

    @Test
    void ADR0017_aCodeAfterSixtySecondsOrForAnEndedImpersonationIsRefused() throws Exception {
        final JsonNode late = start();
        OwnerSql.client().sql("update identity_impersonation set handoff_expires_at = now() - interval '1 second' where id = :id")
                .param("id", UUID.fromString(late.get("id").asString())).update();
        assertThat(exchange(late.get("code").asString()).statusCode()).isEqualTo(401);

        final JsonNode ended = start();
        assertThat(platform.post("/api/platform/impersonations/" + ended.get("id").asString() + "/end").status()).isEqualTo(200);
        assertThat(exchange(ended.get("code").asString()).statusCode()).isEqualTo(401);
        assertThat(exchange("not-a-real-code-" + UUID.randomUUID()).statusCode()).isEqualTo(401);
    }

    @Test
    void ADR0017_impersonatingAnOwnerStillRefusesMoneySecretsPolicyStaffAndImports() throws Exception {
        // Set-up as the Owner itself (not impersonating): a router, a customer and a line to try things on.
        final Api ownerApi = new Api(port, TestTokens.staff(ownerId, tenantId, com.corebalt.isp.shared.security.Permissions.ALL
                .toArray(String[]::new)));
        final String routerId = Catalog.router(ownerApi, "Handoff-01").json().get("router").get("id").asString();
        final String customerId = Catalog.customer(ownerApi, "Kiprono Handoff", "0711000666");
        final String packageId = Catalog.pppoePackage(ownerApi, "Handoff 10");
        final String lineId = ownerApi.post("/api/v1/subscriptions", "{\"customerId\":\"" + customerId + "\",\"packageId\":\""
                + packageId + "\",\"routerId\":\"" + routerId + "\"}").json().get("subscription").get("id").asString();
        assertThat(ownerApi.put("/api/v1/settings/notifications", """
                {"partnerId":"fake-partner-id","senderId":"HANDOFF","apiKey":"fake-at-key"}""").status()).isEqualTo(200);
        assertThat(ownerApi.get("/api/v1/settings/notifications").json().get("provider").asString()).isEqualTo("CELCOM_AFRICA");

        final Api acting = new Api(port, JSON.readTree(exchange(start().get("code").asString()).body()).get("accessToken").asString());
        final Map<String, Api.Response> refused = Map.of(
                "manual payment", acting.postWithKey("/api/v1/payments/manual", UUID.randomUUID().toString(),
                        "{\"customerId\":\"" + customerId + "\",\"amount\":{\"amount\":100000,\"currency\":\"KES\"},\"channel\":\"CASH\",\"reference\":\"HANDOFF-1\"}"),
                "wallet adjustment", acting.postWithKey("/api/v1/customers/" + customerId + "/wallet/adjustments", UUID.randomUUID().toString(),
                        "{\"amount\":{\"amount\":100000,\"currency\":\"KES\"},\"reason\":\"free days\"}"),
                "M-Pesa secrets", acting.put("/api/v1/settings/mpesa", """
                        {"shortcode":"600984","shortcodeType":"PAYBILL","sandbox":true,"consumerKey":"fake-k","consumerSecret":"fake-s","passkey":"fake-p"}"""),
                "staff invitation", acting.post("/api/v1/staff", "{\"email\":\"new@handoff.example.test\",\"displayName\":\"New One\"}"),
                "new PPPoE password", acting.post("/api/v1/subscriptions/" + lineId + "/pppoe-password"),
                "open a line", acting.post("/api/v1/subscriptions", "{\"customerId\":\"" + customerId + "\",\"packageId\":\""
                        + packageId + "\",\"routerId\":\"" + routerId + "\"}"),
                "register a router", acting.post("/api/v1/routers", "{\"name\":\"Handoff-02\",\"tunnelIp\":\"10.209.0.2\"}"),
                "router secret", acting.post("/api/v1/routers/" + routerId + "/radius-secret"),
                "customer import", acting.postCsv("/api/v1/customer-imports?routerId=" + routerId,
                        "name,phone,package\nImported One,0711000777,Handoff 10\n"));
        refused.forEach((what, response) -> assertThat(response.status()).as(what + ": " + response.body()).isEqualTo(403));

        // The SMS settings read without the delivery-report URL, whose token is a secret.
        final Api.Response sms = acting.get("/api/v1/settings/notifications");
        assertThat(sms.status()).isEqualTo(200);
        assertThat(sms.json().hasNonNull("deliveryReportUrl")).isFalse();

        // Operational work stays open.
        assertThat(acting.get("/api/v1/customers").status()).isEqualTo(200);
        assertThat(acting.post("/api/v1/customers", "{\"name\":\"Helped Customer\",\"phone\":\"0711000888\"}").status()).isEqualTo(201);
        // ... and every audited action carries the platform admin behind it, on behalf of the Owner (A1).
        assertThat(acting.postWithKey("/api/v1/subscriptions/" + lineId + "/cancel", UUID.randomUUID().toString(),
                "{\"reason\":\"Opened twice by mistake\"}").status()).isEqualTo(200);
        assertThat(OwnerSql.client().sql("""
                        select actor_type, on_behalf_of from identity_audit_log
                        where tenant_id = :t and action = 'subscriptions.cancelled'
                        """).param("t", tenantId).query().singleRow())
                .containsEntry("actor_type", "PLATFORM").containsEntry("on_behalf_of", ownerId);
    }

    @Test
    void ADR0017_theImpersonatedSessionEndsItself_andANormalTokenCannot() throws Exception {
        final JsonNode started = start();
        final Api acting = new Api(port, JSON.readTree(exchange(started.get("code").asString()).body()).get("accessToken").asString());
        assertThat(acting.post("/api/v1/impersonation/end").status()).isEqualTo(200);
        assertThat(acting.get("/api/v1/me").status()).isEqualTo(401);
        assertThat(OwnerSql.client().sql("select count(*) from identity_audit_log where tenant_id = :t and action = 'identity.impersonation_ended'")
                .param("t", tenantId).query(Long.class).single()).isOne();
        assertThat(new Api(port, TestTokens.staff(ownerId, tenantId, "customers.write")).post("/api/v1/impersonation/end").status())
                .isEqualTo(403);
    }

    private JsonNode start() {
        final Api.Response started = platform.post("/api/platform/tenants/" + tenantId + "/impersonations",
                "{\"staffUserId\":\"" + ownerId + "\",\"reason\":\"Ticket 4711: customer cannot see payments\",\"minutes\":15}");
        assertThat(started.status()).as(started.body()).isEqualTo(200);
        return started.json();
    }

    private HttpResponse<String> exchange(final String code) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/auth/impersonation"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"code\":\"" + code + "\"}")).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode claims(final String token) {
        return JSON.readTree(new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]), StandardCharsets.UTF_8));
    }
}
