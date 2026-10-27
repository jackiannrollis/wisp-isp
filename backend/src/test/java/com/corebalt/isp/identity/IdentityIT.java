package com.corebalt.isp.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.corebalt.isp.identity.application.PlatformAdmins;
import com.corebalt.isp.identity.domain.Credentials;
import com.corebalt.isp.shared.security.Permissions;
import com.corebalt.isp.support.Api;
import com.corebalt.isp.support.Catalog;
import com.corebalt.isp.support.FakeAfricasTalking;
import com.corebalt.isp.support.IntegrationTest;
import com.corebalt.isp.support.OwnerSql;
import com.corebalt.isp.support.TestTenants;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Sign-in end to end (ADR 0010, A1, A2, T1): staff invitations, forced password change and TOTP,
 * lockout, rotating refresh tokens with reuse detection, revocation by token version, portal codes
 * and PINs, platform admins with mandatory TOTP and recovery codes, bootstrap, break-glass and
 * impersonation.
 */
class IdentityIT extends IntegrationTest {

    private static final AtomicInteger PHONES = new AtomicInteger();
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String ORIGIN = "http://localhost:4200";
    /** Obvious fake, used only in this test. */
    private static final String NEW_PASSWORD = "correct-horse-battery-staple";

    @Value("${local.server.port}")
    int port;

    @Autowired JdbcClient jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired PlatformAdmins platformAdmins;
    @Autowired org.springframework.data.redis.core.StringRedisTemplate redis;

    private final HttpClient http = HttpClient.newHttpClient();
    TestTenants.Created tenant;
    Api owner;

    @BeforeEach
    void tenant() {
        tenant = TestTenants.createWithCode(jdbc, tx);
        owner = new Api(port, tenant.id());
        // Every test calls from 127.0.0.1: start each with fresh per-address code limits.
        final java.util.Set<String> keys = redis.keys("otpip:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    // --- staff ---

    @Test
    void A2_anOwnerInvitationForcesAPasswordChangeThenTotpEnrolment() {
        final Invited invited = invite("owner2@isp.example.test", "Owner");
        assertThat(invited.temporaryPassword()).hasSize(14);

        final JsonNode first = post("/api/v1/auth/login", Map.of("tenant", tenant.code(), "email", invited.email(),
                "password", invited.temporaryPassword())).json();
        assertThat(first.get("status").asString()).isEqualTo("CHANGE_PASSWORD");
        assertThat(first.has("accessToken")).isFalse();

        final JsonNode enrol = post("/api/v1/auth/password", Map.of("challenge", first.get("challenge").asString(),
                "newPassword", NEW_PASSWORD)).json();
        assertThat(enrol.get("status").asString()).as("Owner holds finance and admin codes: 2FA (A2)").isEqualTo("TOTP_ENROLL");
        final String secret = enrol.get("totpSecret").asString();
        assertThat(enrol.get("otpauthUri").asString()).startsWith("otpauth://totp/");

        assertThat(post("/api/v1/auth/totp/enroll", Map.of("challenge", enrol.get("challenge").asString(), "code", "000000"))
                .status()).isEqualTo(401);
        final Http signedIn = post("/api/v1/auth/totp/enroll", Map.of("challenge", enrol.get("challenge").asString(),
                "code", totp(secret)));
        assertThat(signedIn.status()).isEqualTo(200);
        assertThat(signedIn.json().get("status").asString()).isEqualTo("SIGNED_IN");
        final String cookie = signedIn.header("Set-Cookie");
        assertThat(cookie).startsWith("isp_rt=").contains("HttpOnly").contains("Secure").contains("SameSite=Strict")
                .contains("Path=/api/v1/auth/session");
        assertThat(signedIn.body()).doesNotContain(cookie.substring(7, 20));

        final JsonNode me = new Api(port, signedIn.json().get("accessToken").asString()).get("/api/v1/me").json();
        assertThat(me.get("email").asString()).isEqualTo(invited.email());
        assertThat(me.get("role").asString()).isEqualTo("Owner");
        assertThat(me.get("permissions").size()).isEqualTo(Permissions.ALL.size()).isEqualTo(16);

        // The next sign-in asks for the code.
        final JsonNode again = post("/api/v1/auth/login", Map.of("tenant", tenant.code().toLowerCase(), "email",
                invited.email().toUpperCase(), "password", NEW_PASSWORD)).json();
        assertThat(again.get("status").asString()).isEqualTo("TOTP_REQUIRED");
        assertThat(post("/api/v1/auth/totp", Map.of("challenge", again.get("challenge").asString(), "code", totp(secret)))
                .json().get("status").asString()).isEqualTo("SIGNED_IN");
    }

    @Test
    void A2_aRoleWithoutFinanceOrAdminCodesSignsInWithoutTotp() {
        final String token = signInWithoutTotp(invite("tech@isp.example.test", "Technician"));
        assertThat(new Api(port, token).get("/api/v1/me").json().get("permissions").toString())
                .contains("network.write").doesNotContain("staff.manage");
    }

    @Test
    void anInvitationExpiresAfter72HoursAndCanOnlyBeRegenerated() {
        final Invited invited = invite("late@isp.example.test", "Support");
        OwnerSql.client().sql("update identity_staff_user set password_expires_at = now() - interval '1 minute' where id = :id")
                .param("id", invited.id()).update();
        assertThat(post("/api/v1/auth/login", Map.of("tenant", tenant.code(), "email", invited.email(),
                "password", invited.temporaryPassword())).status()).isEqualTo(401);

        final JsonNode regenerated = owner.post("/api/v1/staff/" + invited.id() + "/temporary-password").json();
        final String fresh = regenerated.get("temporaryPassword").asString();
        assertThat(fresh).isNotEqualTo(invited.temporaryPassword());
        assertThat(owner.get("/api/v1/staff/" + invited.id()).body()).doesNotContain(fresh);
        assertThat(post("/api/v1/auth/login", Map.of("tenant", tenant.code(), "email", invited.email(), "password", fresh))
                .json().get("status").asString()).isEqualTo("CHANGE_PASSWORD");
    }

    @Test
    void fiveWrongPasswordsLockTheAccountFor15Minutes() {
        final Invited invited = invite("typo@isp.example.test", "Support");
        final Map<String, String> wrong = Map.of("tenant", tenant.code(), "email", invited.email(), "password", "wrong-password-1");
        for (int i = 0; i < 4; i++) {
            final Http refused = post("/api/v1/auth/login", wrong);
            assertThat(refused.status()).isEqualTo(401);
            assertThat(refused.body()).contains("Wrong organisation, email or password.");
        }
        assertThat(post("/api/v1/auth/login", wrong).status()).isEqualTo(401);
        final Http locked = post("/api/v1/auth/login", Map.of("tenant", tenant.code(), "email", invited.email(),
                "password", invited.temporaryPassword()));
        assertThat(locked.status()).isEqualTo(429);
        assertThat(locked.body()).contains("too-many-attempts");
        assertThat(OwnerSql.client().sql("select count(*) from identity_audit_log where target_id = :id and action = 'identity.locked_out'")
                .param("id", invited.id()).query(Long.class).single()).isOne();
    }

    @Test
    void anUnknownEmailAndAWrongPasswordGetTheSameAnswer() {
        final Http unknown = post("/api/v1/auth/login", Map.of("tenant", tenant.code(), "email", "nobody@isp.example.test",
                "password", "whatever-password"));
        final Http otherTenant = post("/api/v1/auth/login", Map.of("tenant", "NOPE", "email", "nobody@isp.example.test",
                "password", "whatever-password"));
        assertThat(unknown.status()).isEqualTo(401);
        assertThat(otherTenant.body()).contains("Wrong organisation, email or password.");
        assertThat(unknown.body()).contains("Wrong organisation, email or password.");
    }

    @Test
    void refreshRotatesTheTokenAndReusingAnOldOneRevokesTheFamily() {
        final Http signedIn = signIn(invite("refresh@isp.example.test", "Technician"));
        final String first = cookieValue(signedIn.header("Set-Cookie"));

        final Http refreshed = refresh("/api/v1/auth/session/refresh", first, ORIGIN);
        assertThat(refreshed.status()).isEqualTo(200);
        assertThat(refreshed.json().get("accessToken").asString()).isNotBlank();
        final String second = cookieValue(refreshed.header("Set-Cookie"));
        assertThat(second).isNotEqualTo(first);

        // The first token was copied: presenting it again ends the whole family.
        assertThat(refresh("/api/v1/auth/session/refresh", first, ORIGIN).status()).isEqualTo(401);
        assertThat(refresh("/api/v1/auth/session/refresh", second, ORIGIN).status()).isEqualTo(401);
        assertThat(OwnerSql.client().sql("select count(*) from identity_refresh_token where token_hash = :h and revoked_at is not null")
                .param("h", Credentials.sha256(second)).query(Long.class).single()).isOne();
    }

    @Test
    void refreshNeedsACorebaltOrigin() {
        final String cookie = cookieValue(signIn(invite("origin@isp.example.test", "Technician")).header("Set-Cookie"));
        assertThat(refresh("/api/v1/auth/session/refresh", cookie, null).status()).isEqualTo(401);
        assertThat(refresh("/api/v1/auth/session/refresh", cookie, "https://evil.example").status()).isEqualTo(401);
        assertThat(refresh("/api/v1/auth/session/refresh", cookie, ORIGIN).status()).isEqualTo(200);
    }

    @Test
    void aRefreshTokenOnlyWorksOnItsOwnApi() {
        final String cookie = cookieValue(signIn(invite("crossapi@isp.example.test", "Technician")).header("Set-Cookie"));
        assertThat(refresh("/api/v1/portal-auth/session/refresh", cookie, ORIGIN).status()).isEqualTo(401);
    }

    @Test
    void logoutRevokesTheRefreshToken() {
        final String cookie = cookieValue(signIn(invite("logout@isp.example.test", "Technician")).header("Set-Cookie"));
        final Http out = refresh("/api/v1/auth/session/logout", cookie, ORIGIN);
        assertThat(out.status()).isEqualTo(204);
        assertThat(out.header("Set-Cookie")).contains("Max-Age=0");
        assertThat(refresh("/api/v1/auth/session/refresh", cookie, ORIGIN).status()).isEqualTo(401);
    }

    @Test
    void disablingAStaffMemberEndsTheirSessionsAtTheNextRequest() {
        final Invited invited = invite("leaver@isp.example.test", "Technician");
        final Http signedIn = signIn(invited);
        final Api leaver = new Api(port, signedIn.json().get("accessToken").asString());
        assertThat(leaver.get("/api/v1/me").status()).isEqualTo(200);

        assertThat(owner.post("/api/v1/staff/" + invited.id() + "/disable", "{\"reason\":\"left the company\"}").status()).isEqualTo(200);

        final Api.Response refused = leaver.get("/api/v1/me");
        assertThat(refused.status()).isEqualTo(401);
        assertThat(refused.body()).contains("This session has ended");
        assertThat(refresh("/api/v1/auth/session/refresh", cookieValue(signedIn.header("Set-Cookie")), ORIGIN).status()).isEqualTo(401);
        assertThat(post("/api/v1/auth/login", Map.of("tenant", tenant.code(), "email", invited.email(), "password", NEW_PASSWORD))
                .status()).isEqualTo(401);
        assertThat(OwnerSql.client().sql("select reason from identity_audit_log where target_id = :id and action = 'identity.staff_disabled'")
                .param("id", invited.id()).query(String.class).single()).isEqualTo("left the company");
    }

    @Test
    void A2_aRoleChangeTakesEffectAtTheNextRequest() {
        final Invited invited = invite("mover@isp.example.test", "Technician");
        final Api mover = new Api(port, signIn(invited).json().get("accessToken").asString());
        final String finance = roleId("Finance");
        assertThat(owner.put("/api/v1/staff/" + invited.id() + "/role", "{\"roleId\":\"" + finance + "\"}").status()).isEqualTo(200);
        assertThat(mover.get("/api/v1/me").status()).isEqualTo(401);
    }

    @Test
    void A2_editingARolesPermissionsEndsTheSessionsOfItsHolders() {
        final JsonNode role = owner.post("/api/v1/roles", "{\"name\":\"Cashier\",\"permissions\":[\"payments.stk\"]}").json();
        assertThat(role.get("requires2fa").asBoolean()).isFalse();
        final Invited invited = invite("cashier@isp.example.test", "Cashier");
        final Api cashier = new Api(port, signIn(invited).json().get("accessToken").asString());
        assertThat(cashier.get("/api/v1/me").status()).isEqualTo(200);

        final JsonNode edited = owner.put("/api/v1/roles/" + role.get("id").asString(),
                "{\"name\":\"Cashier\",\"permissions\":[\"payments.stk\",\"payments.record_manual\"]}").json();
        assertThat(edited.get("requires2fa").asBoolean()).as("record_manual is a finance code (A2)").isTrue();
        assertThat(cashier.get("/api/v1/me").status()).isEqualTo(401);
    }

    @Test
    void theOwnerRoleCannotBeEditedAndUnknownCodesAreRefused() {
        assertThat(owner.put("/api/v1/roles/" + roleId("Owner"), "{\"name\":\"Owner\",\"permissions\":[]}").status()).isEqualTo(409);
        assertThat(owner.post("/api/v1/roles", "{\"name\":\"Root\",\"permissions\":[\"everything\"]}").status()).isEqualTo(400);
    }

    /** A2, ADR 0014: the cutover import's permission is the Owner's alone, like the account settings, and needs 2FA. */
    @Test
    void A2_theCutoverImportPermissionIsHeldByTheOwnerRoleOnly() {
        for (final String ownerOnly : List.of("subscriptions.import_active", "settings.write")) {
            final Api.Response refused = owner.post("/api/v1/roles", "{\"name\":\"Migrator\",\"permissions\":[\"" + ownerOnly + "\"]}");
            assertThat(refused.status()).as(refused.body()).isEqualTo(400);
            assertThat(refused.body()).contains("held by the Owner role only");
        }
        for (final JsonNode role : owner.get("/api/v1/roles").json()) {
            if (role.get("name").asString().equals("Owner")) {
                assertThat(role.get("permissions").toString()).contains("subscriptions.import_active");
                assertThat(role.get("requires2fa").asBoolean()).isTrue();
            }
        }
    }

    @Test
    void staffManagementNeedsItsPermission() {
        final Api support = new Api(port, tenant.id(), "customers.write");
        assertThat(support.get("/api/v1/staff").status()).isEqualTo(403);
        assertThat(support.post("/api/v1/staff", "{}").status()).isEqualTo(403);
        assertThat(support.get("/api/v1/audit-log").status()).isEqualTo(403);
        assertThat(support.get("/api/v1/roles").status()).isEqualTo(200);
    }

    @Test
    void A1_theAuditLogShowsWhoDidWhat() {
        final Invited invited = invite("audited@isp.example.test", "Support");
        final JsonNode page = owner.get("/api/v1/audit-log?action=identity.staff_invited").json();
        assertThat(page.get("total").asLong()).isOne();
        final JsonNode row = page.get("items").get(0);
        assertThat(row.get("actorType").asString()).isEqualTo("STAFF");
        assertThat(row.get("actorId").asString()).isEqualTo(owner.staffId().toString());
        assertThat(row.get("targetId").asString()).isEqualTo(invited.id().toString());
        assertThat(row.toString()).doesNotContain(invited.temporaryPassword());
    }

    @Test
    void ownPasswordChangeEndsEverySession() {
        final Invited invited = invite("self@isp.example.test", "Technician");
        final Api self = new Api(port, signIn(invited).json().get("accessToken").asString());
        assertThat(self.post("/api/v1/me/password", "{\"currentPassword\":\"nope-nope-nope\",\"newPassword\":\"another-long-passphrase\"}")
                .status()).isEqualTo(401);
        assertThat(self.post("/api/v1/me/password", "{\"currentPassword\":\"" + NEW_PASSWORD + "\",\"newPassword\":\"another-long-passphrase\"}")
                .status()).isEqualTo(204);
        assertThat(self.get("/api/v1/me").status()).isEqualTo(401);
        assertThat(post("/api/v1/auth/login", Map.of("tenant", tenant.code(), "email", invited.email(), "password",
                "another-long-passphrase")).json().get("status").asString()).isEqualTo("SIGNED_IN");
    }

    @Test
    void nobodyCanDisableOrReroleThemselvesAndATenantKeepsAStaffManager() {
        assertThat(owner.post("/api/v1/staff/" + owner.staffId() + "/disable", "{}").status()).isEqualTo(409);
        final Invited onlyOwner = invite("onlyowner@isp.example.test", "Owner");
        // The test Api's own staff row has no role, so onlyOwner is the tenant's one staff manager.
        assertThat(owner.post("/api/v1/staff/" + onlyOwner.id() + "/disable", "{}").status()).isEqualTo(409);
    }

    @Test
    void Q11_M3_aTemporaryPasswordMaySendBySmsMaskedInTheLogAndAudited() {
        customerWithSms("Sms Setup"); // configures the tenant's SMS account
        final String e164 = phone();
        final String local = "0" + e164.substring(4);

        // No phone: refused before any invitation exists.
        assertThat(owner.post("/api/v1/staff?sendSms=true", "{\"email\":\"nophone@isp.example.test\",\"displayName\":\"No Phone\","
                + "\"roleId\":\"" + roleId("Support") + "\"}").status()).isEqualTo(409);
        assertThat(OwnerSql.client().sql("select count(*) from identity_staff_user where email = 'nophone@isp.example.test'")
                .query(Long.class).single()).isZero();

        final Api.Response invited = owner.post("/api/v1/staff?sendSms=true", "{\"email\":\"sms@isp.example.test\","
                + "\"displayName\":\"Sms Staff\",\"phone\":\"" + local + "\",\"roleId\":\"" + roleId("Support") + "\"}");
        assertThat(invited.status()).as(invited.body()).isEqualTo(201);
        final String password = invited.json().get("temporaryPassword").asString();
        assertThat(invited.json().get("smsDelivery").asString()).isEqualTo("SENT");
        assertThat(invited.json().get("staff").get("phone").asString()).as("stored in E.164").isEqualTo(e164);
        final String message = AT.requestsTo(e164).getLast().form().get("message");
        assertThat(message).contains(password).contains("72 hours").hasSizeLessThanOrEqualTo(160);

        final String id = invited.json().get("staff").get("id").asString();
        final String reset = owner.post("/api/v1/staff/" + id + "/temporary-password?sendSms=true").json()
                .get("temporaryPassword").asString();
        assertThat(AT.requestsTo(e164).getLast().form().get("message")).contains(reset);

        // Never stored or audited in clear (A3): the log body is masked, the audit row names only the outcome.
        assertThat(OwnerSql.client().sql("""
                        select body from notifications_notification where tenant_id = :t and template_key = 'STAFF_TEMPORARY_PASSWORD'
                        """).param("t", tenant.id()).query(String.class).list())
                .hasSize(2).allSatisfy(body -> assertThat(body).contains("**************").doesNotContain(password)
                        .doesNotContain(reset));
        assertThat(OwnerSql.client().sql("""
                        select coalesce(after_state::text, '') from identity_audit_log
                        where tenant_id = :t and action = 'identity.temporary_password_sms'
                        """).param("t", tenant.id()).query(String.class).list())
                .hasSize(2).allSatisfy(after -> assertThat(after).contains("SENT").doesNotContain(password));

        // The SMS changes nothing else: the same expiry and forced change at first sign-in.
        final JsonNode first = post("/api/v1/auth/login", Map.of("tenant", tenant.code(), "email", "sms@isp.example.test",
                "password", reset)).json();
        assertThat(first.get("status").asString()).isEqualTo("CHANGE_PASSWORD");
    }

    // --- portal ---

    @Test
    void thePortalCodeSetsAPinAndThePinSignsIn() {
        final Customer customer = customerWithSms("Akinyi Otieno");
        final Http requested = post("/api/v1/portal-auth/code", Map.of("tenant", tenant.code(), "phone", customer.local()));
        assertThat(requested.status()).isEqualTo(202);
        final String code = smsCode(customer.e164());
        assertThat(OwnerSql.client().sql("select body from notifications_notification where tenant_id = :t and template_key = 'SIGN_IN_CODE'")
                .param("t", tenant.id()).query(String.class).single())
                .as("the message log never holds the code (M3)").contains("******").doesNotContain(code);

        final JsonNode verified = post("/api/v1/portal-auth/code/verify", Map.of("tenant", tenant.code(), "phone",
                customer.e164(), "code", code)).json();
        assertThat(verified.get("status").asString()).isEqualTo("PIN_SETUP");
        assertThat(post("/api/v1/portal-auth/pin", Map.of("challenge", verified.get("challenge").asString(), "pin", "1234"))
                .status()).as("a run like 1234 is refused").isEqualTo(400);
        final Http pinSet = post("/api/v1/portal-auth/pin", Map.of("challenge", verified.get("challenge").asString(), "pin", "4719"));
        assertThat(pinSet.json().get("status").asString()).isEqualTo("SIGNED_IN");
        assertThat(pinSet.header("Set-Cookie")).contains("Path=/api/v1/portal-auth/session");

        final String portalToken = pinSet.json().get("accessToken").asString();
        assertThat(new Api(port, portalToken).get("/api/v1/customers").status()).as("a customer token is not staff").isEqualTo(403);

        final JsonNode login = post("/api/v1/portal-auth/login", Map.of("tenant", tenant.code(), "account", customer.number(),
                "pin", "4719")).json();
        assertThat(login.get("status").asString()).isEqualTo("SIGNED_IN");
        assertThat(post("/api/v1/portal-auth/login", Map.of("tenant", tenant.code(), "account", customer.local(), "pin", "4719"))
                .status()).as("the phone works when it names one account").isEqualTo(200);
    }

    @Test
    void aCodeAllowsThreeAttemptsThenANewCodeIsNeeded() {
        final Customer customer = customerWithSms("Baraka Mwangi");
        post("/api/v1/portal-auth/code", Map.of("tenant", tenant.code(), "phone", customer.e164()));
        final String code = smsCode(customer.e164());
        final String wrong = code.equals("000000") ? "111111" : "000000";
        for (int i = 0; i < 3; i++) {
            assertThat(post("/api/v1/portal-auth/code/verify", Map.of("tenant", tenant.code(), "phone", customer.e164(),
                    "code", wrong)).status()).isEqualTo(401);
        }
        assertThat(post("/api/v1/portal-auth/code/verify", Map.of("tenant", tenant.code(), "phone", customer.e164(),
                "code", code)).status()).as("used up after three attempts").isEqualTo(401);
    }

    @Test
    void codesAreLimitedPerPhone() {
        final Customer customer = customerWithSms("Chebet Kiprono");
        for (int i = 0; i < 3; i++) {
            assertThat(post("/api/v1/portal-auth/code", Map.of("tenant", tenant.code(), "phone", customer.e164())).status())
                    .isEqualTo(202);
        }
        assertThat(post("/api/v1/portal-auth/code", Map.of("tenant", tenant.code(), "phone", customer.e164())).status())
                .isEqualTo(429);
        assertThat(AT.requestsTo(customer.e164())).hasSize(3);
    }

    @Test
    void anUnknownPhoneGetsTheSameAnswerAndNoMessage() {
        customerWithSms("Dalmas Ouma");
        final String stranger = phone();
        final Http answer = post("/api/v1/portal-auth/code", Map.of("tenant", tenant.code(), "phone", stranger));
        assertThat(answer.status()).isEqualTo(202);
        assertThat(answer.body()).contains("If this number belongs to an account");
        assertThat(AT.requestsTo(stranger)).isEmpty();
    }

    @Test
    void fiveWrongPinsLockThePortalAccount() {
        final Customer customer = customerWithSms("Esther Njeri");
        post("/api/v1/portal-auth/code", Map.of("tenant", tenant.code(), "phone", customer.e164()));
        final String challenge = post("/api/v1/portal-auth/code/verify", Map.of("tenant", tenant.code(), "phone", customer.e164(),
                "code", smsCode(customer.e164()))).json().get("challenge").asString();
        post("/api/v1/portal-auth/pin", Map.of("challenge", challenge, "pin", "5820"));
        for (int i = 0; i < 5; i++) {
            assertThat(post("/api/v1/portal-auth/login", Map.of("tenant", tenant.code(), "account", customer.number(), "pin", "9999"))
                    .status()).isEqualTo(401);
        }
        assertThat(post("/api/v1/portal-auth/login", Map.of("tenant", tenant.code(), "account", customer.number(), "pin", "5820"))
                .status()).isEqualTo(429);
    }

    @Test
    void severalAccountsOnOnePhoneChooseWhichToSetUp() {
        final Customer first = customerWithSms("Faith Wambui");
        Catalog.customer(owner, "Faith Wambui (shop)", first.local());
        post("/api/v1/portal-auth/code", Map.of("tenant", tenant.code(), "phone", first.e164()));
        final JsonNode verified = post("/api/v1/portal-auth/code/verify", Map.of("tenant", tenant.code(), "phone", first.e164(),
                "code", smsCode(first.e164()))).json();
        assertThat(verified.get("status").asString()).isEqualTo("CHOOSE_ACCOUNT");
        assertThat(verified.get("accounts")).hasSize(2);
        assertThat(post("/api/v1/portal-auth/login", Map.of("tenant", tenant.code(), "account", first.local(), "pin", "4719"))
                .status()).as("an ambiguous phone cannot sign in").isEqualTo(401);
    }

    // --- platform ---

    @Test
    void platformBootstrapRunsOnlyOnAnEmptyTableAndTotpIsMandatory() {
        resetPlatformAdmins();
        final long auditBefore = OwnerSql.client().sql(
                "select count(*) from identity_platform_audit_log where action = 'platform.admin_bootstrapped'").query(Long.class).single();
        assertThat(platformAdmins.bootstrap("root@corebalt.example.test", "bootstrap-password-fake"))
                .isEqualTo(PlatformAdmins.Bootstrap.CREATED);
        assertThat(platformAdmins.bootstrap("second@corebalt.example.test", "bootstrap-password-fake"))
                .isEqualTo(PlatformAdmins.Bootstrap.IGNORED_NOT_EMPTY);
        assertThat(OwnerSql.client().sql("select count(*) from identity_platform_admin").query(Long.class).single()).isOne();
        assertThat(OwnerSql.client().sql("select count(*) from identity_platform_audit_log where action = 'platform.admin_bootstrapped'")
                .query(Long.class).single()).isEqualTo(auditBefore + 1);

        final JsonNode first = post("/api/platform/auth/login", Map.of("email", "root@corebalt.example.test",
                "password", "bootstrap-password-fake")).json();
        assertThat(first.get("status").asString()).isEqualTo("CHANGE_PASSWORD");
        final JsonNode enrol = post("/api/platform/auth/password", Map.of("challenge", first.get("challenge").asString(),
                "newPassword", NEW_PASSWORD)).json();
        assertThat(enrol.get("status").asString()).as("no platform token without TOTP").isEqualTo("TOTP_ENROLL");
        assertThat(enrol.get("recoveryCodes")).hasSize(10);
        final String secret = enrol.get("totpSecret").asString();
        final JsonNode signedIn = post("/api/platform/auth/totp/enroll", Map.of("challenge", enrol.get("challenge").asString(),
                "code", totp(secret))).json();
        final Api platform = new Api(port, signedIn.get("accessToken").asString());
        assertThat(platform.get("/api/platform/tenants").status()).isEqualTo(200);
        assertThat(platform.get("/api/v1/customers").status()).as("a platform token is not a tenant token").isEqualTo(403);

        // A recovery code works once in place of the TOTP code.
        final String recovery = enrol.get("recoveryCodes").get(0).asString();
        assertThat(recovery).matches("[A-Z0-9]{4}-[A-Z0-9]{4}");
        assertThat(totpStep(recovery).get("status").asString()).isEqualTo("SIGNED_IN");
        assertThat(totpStepStatus(recovery)).isEqualTo(401);
    }

    @Test
    void breakGlassResetForcesANewPasswordAndTotpAndEndsSessions() {
        final Api platform = platformAdmin();
        assertThat(platform.get("/api/platform/tenants").status()).isEqualTo(200);
        final String temporary = platformAdmins.breakGlassReset("root@corebalt.example.test", "test-operator").orElseThrow();
        assertThat(platform.get("/api/platform/tenants").status()).isEqualTo(401);
        final JsonNode next = post("/api/platform/auth/login", Map.of("email", "root@corebalt.example.test", "password", temporary)).json();
        assertThat(next.get("status").asString()).isEqualTo("CHANGE_PASSWORD");
        assertThat(OwnerSql.client().sql("select detail->>'operator' from identity_platform_audit_log where action = 'platform.admin_break_glass_reset'")
                .query(String.class).list()).contains("test-operator");
        assertThat(platformAdmins.breakGlassReset("nobody@corebalt.example.test", "test-operator")).isEmpty();
    }

    @Test
    void A1_impersonationIsCappedAuditedForTheTenantAndEndsAtOnce() {
        final Api platform = platformAdmin();
        final Invited target = invite("helped@isp.example.test", "Support");
        signInWithoutTotp(target);
        assertThat(platform.post("/api/platform/tenants/" + tenant.id() + "/impersonations",
                "{\"staffUserId\":\"" + target.id() + "\",\"reason\":\"ticket 42\",\"minutes\":31}").status()).isEqualTo(400);

        final JsonNode started = platform.post("/api/platform/tenants/" + tenant.id() + "/impersonations",
                "{\"staffUserId\":\"" + target.id() + "\",\"reason\":\"ticket 42\",\"minutes\":10}").json();
        // ADR 0017: the start answers a single-use code; the admin app exchanges it for the token.
        final Api acting = new Api(port, post("/api/v1/auth/impersonation", Map.of("code", started.get("code").asString()))
                .json().get("accessToken").asString());
        final JsonNode me = acting.get("/api/v1/me").json();
        assertThat(me.get("id").asString()).isEqualTo(target.id().toString());
        assertThat(me.get("impersonatedBy").asString()).isNotBlank();

        final List<Map<String, Object>> rows = OwnerSql.client().sql("""
                        select actor_type, on_behalf_of, reason from identity_audit_log
                        where tenant_id = :t and action = 'identity.impersonation_started'
                        """).param("t", tenant.id()).query().listOfRows();
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst()).containsEntry("actor_type", "PLATFORM").containsEntry("on_behalf_of", target.id())
                .containsEntry("reason", "ticket 42");
        assertThat(owner.get("/api/v1/audit-log?action=identity.impersonation").json().get("items").get(0)
                .get("actorName").asString()).isEqualTo("Corebalt support");

        assertThat(platform.post("/api/platform/impersonations/" + started.get("id").asString() + "/end").status()).isEqualTo(200);
        assertThat(acting.get("/api/v1/me").status()).isEqualTo(401);
        assertThat(OwnerSql.client().sql("select count(*) from identity_audit_log where tenant_id = :t and action = 'identity.impersonation_ended'")
                .param("t", tenant.id()).query(Long.class).single()).isOne();
        assertThat(OwnerSql.client().sql("select expires_at - started_at <= interval '30 minutes' from identity_impersonation where id = :id")
                .param("id", UUID.fromString(started.get("id").asString())).query(Boolean.class).single()).isTrue();
    }

    @Test
    void thePlatformOnboardsATenantWithAnOwnerInvitation() {
        final Api platform = platformAdmin();
        final String code = "Q" + (char) ('A' + PHONES.incrementAndGet() % 26) + (char) ('A' + (System.nanoTime() % 26));
        final Api.Response badOwner = platform.post("/api/platform/tenants", """
                {"code":"%s","name":"Quick Net","subdomain":"%s","ownerEmail":"not-an-email","ownerName":"Boss"}
                """.formatted(code, code.toLowerCase() + "net"));
        assertThat(badOwner.status()).isEqualTo(400);
        assertThat(OwnerSql.client().sql("select count(*) from tenancy_tenant where code = :c").param("c", code)
                .query(Long.class).single()).as("no tenant left behind").isZero();
        final Api.Response created = platform.post("/api/platform/tenants", """
                {"code":"%s","name":"Quick Net","subdomain":"%s","ownerEmail":"boss@quick.example.test","ownerName":"Boss"}
                """.formatted(code, code.toLowerCase() + "net"));
        assertThat(created.status()).as(created.body()).isEqualTo(201);
        final String password = created.json().get("owner").get("temporaryPassword").asString();
        assertThat(post("/api/v1/auth/login", Map.of("tenant", code, "email", "boss@quick.example.test", "password", password))
                .json().get("status").asString()).isEqualTo("CHANGE_PASSWORD");
    }

    @Test
    void A1_platformTenantStatusTransitionsAreAuditedAndASuspendedTenantRefusesSignIns() {
        final Api platform = platformAdmin();
        final String code = "S" + (char) ('A' + PHONES.incrementAndGet() % 26) + (char) ('A' + (System.nanoTime() % 26));
        final JsonNode created = platform.post("/api/platform/tenants", """
                {"code":"%s","name":"Status Net","subdomain":"%s","ownerEmail":"boss@status.example.test","ownerName":"Boss"}
                """.formatted(code, code.toLowerCase() + "net")).json();
        final String tenantId = created.get("tenant").get("id").asString();
        final String password = created.get("owner").get("temporaryPassword").asString();
        final String base = "/api/platform/tenants/" + tenantId;
        final Map<String, String> login = Map.of("tenant", code, "email", "boss@status.example.test", "password", password);
        assertThat(created.get("tenant").get("status").asString()).isEqualTo("TRIAL");

        assertThat(platform.post(base + "/reinstate").status()).as("only a suspended tenant is reinstated").isEqualTo(409);
        assertThat(platform.post(base + "/activate").json().get("status").asString()).isEqualTo("ACTIVE");
        assertThat(platform.post(base + "/activate").status()).as("already active").isEqualTo(409);
        assertThat(platform.post(base + "/suspend", "{\"reason\":\" \"}").status()).as("a reason is required").isEqualTo(400);
        assertThat(platform.post(base + "/suspend", "{\"reason\":\"invoice unpaid\"}").json().get("status").asString()).isEqualTo("SUSPENDED");
        assertThat(post("/api/v1/auth/login", login).status()).as("staff cannot sign in while suspended").isEqualTo(401);
        // The SMS-code request answers 202 whatever it finds (no enumeration) and sends nothing; the PIN
        // sign-in is where a customer of a suspended tenant is refused.
        assertThat(post("/api/v1/portal-auth/login", Map.of("tenant", code, "account", code + "000001", "pin", "4071")).status())
                .as("nor can customers sign in").isEqualTo(401);
        assertThat(platform.post(base + "/reinstate").json().get("status").asString()).isEqualTo("ACTIVE");
        assertThat(post("/api/v1/auth/login", login).json().get("status").asString()).isEqualTo("CHANGE_PASSWORD");

        assertThat(OwnerSql.client().sql("""
                        select action from identity_audit_log
                        where tenant_id = :t and actor_type = 'PLATFORM' and target_type = 'tenant' order by id
                        """).param("t", UUID.fromString(tenantId)).query(String.class).list())
                .containsExactly("identity.tenant_created", "identity.tenant_activated", "identity.tenant_suspended",
                        "identity.tenant_reinstated");
        assertThat(OwnerSql.client().sql("""
                        select detail->>'reason' from identity_platform_audit_log
                        where action = 'platform.tenant_suspended' and target_id = :t
                        """).param("t", UUID.fromString(tenantId)).query(String.class).list()).containsExactly("invoice unpaid");
    }

    /**
     * ADR 0016: the names the web front serves itself (admin, pay, console, ...) cannot be a tenant's
     * portal subdomain; the console lists a tenant's staff of every status, so an Owner who never signed
     * in can be found and their invitation reissued.
     */
    @Test
    void ADR0016_platformNamesAreReservedAndTheConsoleSeesInvitedStaff() {
        final Api platform = platformAdmin();
        for (final String reserved : List.of("admin", "pay", "console", "hub-a")) {
            final Api.Response refused = platform.post("/api/platform/tenants", """
                    {"code":"RSV","name":"Reserved Net","subdomain":"%s","ownerEmail":"boss@reserved.example.test","ownerName":"Boss"}
                    """.formatted(reserved));
            assertThat(refused.status()).as(reserved).isEqualTo(400);
            assertThat(refused.body()).contains("reserved for the platform");
        }
        assertThat(platform.get("/api/platform/tenants").body()).doesNotContain("\"code\":\"RSV\"");

        final String code = "S" + (char) ('A' + PHONES.incrementAndGet() % 26) + (char) ('A' + (System.nanoTime() % 26));
        final JsonNode created = platform.post("/api/platform/tenants", """
                {"code":"%s","name":"Staff Net","subdomain":"%s","ownerEmail":"boss@staff.example.test","ownerName":"Boss"}
                """.formatted(code, code.toLowerCase() + "net")).json();
        final String tenantId = created.get("tenant").get("id").asString();
        final JsonNode all = platform.get("/api/platform/tenants/" + tenantId + "/staff").json();
        assertThat(all.get("total").asLong()).isOne();
        assertThat(all.get("items").get(0).get("status").asString()).isEqualTo("INVITED");
        assertThat(platform.get("/api/platform/tenants/" + tenantId + "/staff?status=ACTIVE").json().get("total").asLong()).isZero();
        assertThat(platform.get("/api/platform/tenants/" + UUID.randomUUID() + "/staff").status()).isEqualTo(404);
    }

    /** ADR 0014: the console opens a tenant's cutover window for at most 30 days; both audit logs record it. */
    @Test
    void ADR0014_thePlatformOpensAndClosesTheCutoverWindowWithinThirtyDays() {
        final Api platform = platformAdmin();
        final String code = "W" + (char) ('A' + PHONES.incrementAndGet() % 26) + (char) ('A' + (System.nanoTime() % 26));
        final JsonNode created = platform.post("/api/platform/tenants", """
                {"code":"%s","name":"Window Net","subdomain":"%s","ownerEmail":"boss@window.example.test","ownerName":"Boss"}
                """.formatted(code, code.toLowerCase() + "net")).json();
        final String tenantId = created.get("tenant").get("id").asString();
        assertThat(created.get("tenant").get("cutoverWindowUntil").isNull()).isTrue();
        final String path = "/api/platform/tenants/" + tenantId + "/cutover-window";

        assertThat(platform.put(path, "{\"until\":\"" + Instant.now().plus(Duration.ofDays(31)) + "\"}").status()).isEqualTo(400);
        assertThat(platform.put(path, "{\"until\":\"" + Instant.now().minus(Duration.ofHours(1)) + "\"}").status()).isEqualTo(400);
        final Instant until = Instant.now().plus(Duration.ofDays(7)).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        final Api.Response opened = platform.put(path, "{\"until\":\"" + until + "\"}");
        assertThat(opened.status()).as(opened.body()).isEqualTo(200);
        assertThat(Instant.parse(opened.json().get("cutoverWindowUntil").asString())).isEqualTo(until);
        assertThat(platform.get("/api/platform/tenants").body()).contains(until.toString());
        assertThat(new Api(port, UUID.fromString(tenantId)).get("/api/v1/settings/account").json().get("cutoverWindowUntil").asString())
                .as("the tenant sees its window in the account settings").isEqualTo(until.toString());

        final Api.Response closed = platform.delete(path);
        assertThat(closed.status()).isEqualTo(200);
        assertThat(closed.json().get("cutoverWindowUntil").isNull()).isTrue();
        assertThat(OwnerSql.client().sql("select action from identity_audit_log where tenant_id = :t and action like 'identity.cutover_window_%' order by created_at")
                .param("t", UUID.fromString(tenantId)).query(String.class).list())
                .containsExactly("identity.cutover_window_opened", "identity.cutover_window_closed");
        assertThat(OwnerSql.client().sql("select count(*) from identity_platform_audit_log where action like 'platform.cutover_window_%' and target_id = :t")
                .param("t", UUID.fromString(tenantId)).query(Long.class).single()).isEqualTo(2);
        assertThat(platform.put("/api/platform/tenants/" + UUID.randomUUID() + "/cutover-window", "{\"until\":\"" + until + "\"}").status())
                .isEqualTo(404);
    }

    @Test
    void platformReissuesAnUnusedInvitationButNeverAnActiveMembersPassword() {
        final Api platform = platformAdmin();
        final String code = "R" + (char) ('A' + PHONES.incrementAndGet() % 26) + (char) ('A' + (System.nanoTime() % 26));
        final JsonNode created = platform.post("/api/platform/tenants", """
                {"code":"%s","name":"Reissue Net","subdomain":"%s","ownerEmail":"boss@reissue.example.test","ownerName":"Boss"}
                """.formatted(code, code.toLowerCase() + "net")).json();
        final String tenantId = created.get("tenant").get("id").asString();
        final String ownerId = created.get("owner").get("staff").get("id").asString();
        final String first = created.get("owner").get("temporaryPassword").asString();
        final String path = "/api/platform/tenants/" + tenantId + "/staff/" + ownerId + "/invitation";

        final Api.Response reissued = platform.post(path);
        assertThat(reissued.status()).as(reissued.body()).isEqualTo(200);
        assertThat(reissued.header("Cache-Control")).contains("no-store");
        final String second = reissued.json().get("temporaryPassword").asString();
        assertThat(second).isNotEqualTo(first).hasSize(14);
        assertThat(post("/api/v1/auth/login", Map.of("tenant", code, "email", "boss@reissue.example.test", "password", first)).status())
                .as("the lost password is dead").isEqualTo(401);
        final JsonNode change = post("/api/v1/auth/login", Map.of("tenant", code, "email", "boss@reissue.example.test", "password", second)).json();
        assertThat(change.get("status").asString()).isEqualTo("CHANGE_PASSWORD");

        // The Owner finishes the first sign-in (new password, TOTP: the role requires it): now active, the
        // console cannot reset them any more; that is the tenant's own business, or an impersonation.
        final JsonNode enrol = post("/api/v1/auth/password", Map.of("challenge", change.get("challenge").asString(),
                "newPassword", NEW_PASSWORD)).json();
        assertThat(enrol.get("status").asString()).isEqualTo("TOTP_ENROLL");
        assertThat(post("/api/v1/auth/totp/enroll", Map.of("challenge", enrol.get("challenge").asString(),
                "code", totp(enrol.get("totpSecret").asString()))).json().get("status").asString()).isEqualTo("SIGNED_IN");
        assertThat(platform.post(path).status()).as("an active member is not reset from the platform").isEqualTo(409);
        assertThat(platform.post("/api/platform/tenants/" + tenantId + "/staff/" + UUID.randomUUID() + "/invitation").status())
                .isEqualTo(404);

        final List<String> tenantLog = OwnerSql.client().sql("""
                        select action || ' ' || actor_type || ' ' || coalesce(after_state::text, '') from identity_audit_log
                        where tenant_id = :t and action = 'identity.invitation_regenerated'
                        """).param("t", UUID.fromString(tenantId)).query(String.class).list();
        assertThat(tenantLog).hasSize(1);
        assertThat(tenantLog.get(0)).startsWith("identity.invitation_regenerated PLATFORM").doesNotContain(second);
        assertThat(OwnerSql.client().sql("""
                        select detail::text from identity_platform_audit_log where action = 'platform.invitation_reissued' and target_id = :s
                        """).param("s", UUID.fromString(ownerId)).query(String.class).single()).contains(code).doesNotContain(second);
    }

    // --- helpers ---

    record Invited(UUID id, String email, String temporaryPassword) {}

    record Customer(String id, String number, String e164, String local) {}

    record Http(int status, String body, HttpResponse<String> raw) {
        JsonNode json() {
            return JSON.readTree(body);
        }

        String header(final String name) {
            return raw.headers().firstValue(name).orElse(null);
        }
    }

    private Invited invite(final String email, final String role) {
        final Api.Response response = owner.post("/api/v1/staff", "{\"email\":\"" + email + "\",\"displayName\":\"" + email
                + "\",\"roleId\":\"" + roleId(role) + "\"}");
        assertThat(response.status()).as(response.body()).isEqualTo(201);
        return new Invited(UUID.fromString(response.json().get("staff").get("id").asString()), email,
                response.json().get("temporaryPassword").asString());
    }

    private String roleId(final String name) {
        for (final JsonNode role : owner.get("/api/v1/roles").json()) {
            if (role.get("name").asString().equals(name)) {
                return role.get("id").asString();
            }
        }
        throw new IllegalStateException("no role " + name);
    }

    /** First sign-in for a role without 2FA: change the temporary password, get the tokens. */
    private Http signIn(final Invited invited) {
        final JsonNode first = post("/api/v1/auth/login", Map.of("tenant", tenant.code(), "email", invited.email(),
                "password", invited.temporaryPassword())).json();
        final Http signedIn = post("/api/v1/auth/password", Map.of("challenge", first.get("challenge").asString(),
                "newPassword", NEW_PASSWORD));
        assertThat(signedIn.json().get("status").asString()).isEqualTo("SIGNED_IN");
        return signedIn;
    }

    private String signInWithoutTotp(final Invited invited) {
        return signIn(invited).json().get("accessToken").asString();
    }

    private Customer customerWithSms(final String name) {
        AT.registerSender("LABISP");
        assertThat(owner.put("/api/v1/settings/notifications", """
                {"atUsername":"sandbox","senderId":"LABISP","sandbox":true,"apiKey":"%s","windowStart":"00:00","windowEnd":"23:59"}
                """.formatted(FakeAfricasTalking.API_KEY)).status()).isEqualTo(200);
        final String e164 = phone();
        final String local = "0" + e164.substring(4);
        final String id = Catalog.customer(owner, name, local);
        return new Customer(id, owner.get("/api/v1/customers/" + id).json().get("number").asString(), e164, local);
    }

    private static String phone() {
        return String.format("+25471%07d", 2_000_000 + PHONES.incrementAndGet());
    }

    private static String smsCode(final String e164) {
        final String message = AT.requestsTo(e164).getLast().form().get("message");
        final Matcher matcher = Pattern.compile("^(\\d{6}) is your ").matcher(message);
        assertThat(matcher.find()).as(message).isTrue();
        return matcher.group(1);
    }

    private static String totp(final String secret) {
        return Credentials.totp(secret, Instant.now().getEpochSecond() / 30);
    }

    private static String cookieValue(final String setCookie) {
        return setCookie.substring("isp_rt=".length(), setCookie.indexOf(';'));
    }

    private void resetPlatformAdmins() {
        final JdbcClient sql = OwnerSql.client();
        sql.sql("delete from identity_impersonation").update();
        sql.sql("delete from identity_platform_recovery_code").update();
        sql.sql("delete from identity_refresh_token where subject_type = 'PLATFORM'").update();
        sql.sql("delete from identity_platform_admin").update();
    }

    private String platformSecret;

    /** A fresh platform admin, signed in through the whole flow. */
    private Api platformAdmin() {
        resetPlatformAdmins();
        platformAdmins.bootstrap("root@corebalt.example.test", "bootstrap-password-fake");
        final JsonNode first = post("/api/platform/auth/login", Map.of("email", "root@corebalt.example.test",
                "password", "bootstrap-password-fake")).json();
        final JsonNode enrol = post("/api/platform/auth/password", Map.of("challenge", first.get("challenge").asString(),
                "newPassword", NEW_PASSWORD)).json();
        platformSecret = enrol.get("totpSecret").asString();
        final JsonNode signedIn = post("/api/platform/auth/totp/enroll", Map.of("challenge", enrol.get("challenge").asString(),
                "code", totp(platformSecret))).json();
        return new Api(port, signedIn.get("accessToken").asString());
    }

    private JsonNode totpStep(final String code) {
        final JsonNode login = post("/api/platform/auth/login", Map.of("email", "root@corebalt.example.test",
                "password", NEW_PASSWORD)).json();
        return post("/api/platform/auth/totp", Map.of("challenge", login.get("challenge").asString(), "code", code)).json();
    }

    private int totpStepStatus(final String code) {
        final JsonNode login = post("/api/platform/auth/login", Map.of("email", "root@corebalt.example.test",
                "password", NEW_PASSWORD)).json();
        return post("/api/platform/auth/totp", Map.of("challenge", login.get("challenge").asString(), "code", code)).status();
    }

    private Http post(final String path, final Map<String, String> body) {
        return send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body), StandardCharsets.UTF_8)));
    }

    private Http refresh(final String path, final String cookie, final String origin) {
        final HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Cookie", "isp_rt=" + cookie)
                .POST(HttpRequest.BodyPublishers.noBody());
        if (origin != null) {
            request.header("Origin", origin);
        }
        return send(request);
    }

    private Http send(final HttpRequest.Builder request) {
        try {
            final HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            return new Http(response.statusCode(), response.body(), response);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
