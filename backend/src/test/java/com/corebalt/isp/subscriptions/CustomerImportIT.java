package com.corebalt.isp.subscriptions;

import static org.assertj.core.api.Assertions.assertThat;

import com.corebalt.isp.support.Api;
import com.corebalt.isp.support.Catalog;
import com.corebalt.isp.support.IntegrationTest;
import com.corebalt.isp.support.OwnerSql;
import com.corebalt.isp.support.TestTenants;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * CSV import during onboarding (docs/07 checklist step 4): all rows or none, credentials shown once;
 * and the cutover import (ADR 0014, S11): a preview, lines active with the expiry already paid for,
 * kept credentials, old account references.
 */
class CustomerImportIT extends IntegrationTest {

    @Value("${local.server.port}")
    int port;

    @Autowired JdbcClient jdbc;
    @Autowired TransactionTemplate tx;

    TestTenants.Created tenant;
    Api api;
    String routerId;

    @BeforeEach
    void catalog() {
        tenant = TestTenants.createWithCode(jdbc, tx);
        api = new Api(port, tenant.id());
        Catalog.pppoePackage(api, "Home 10");
        routerId = Catalog.router(api, "Utawala-01").json().get("router").get("id").asString();
    }

    @Test
    void T2_importsCustomersInFileOrderWithLinesAndOneTimePasswords() {
        final String csv =
                """
                name,phone,email,package,installation_location,lat,lng
                Wanjiku Kamau,0712 345 678,wanjiku@example.test,Home 10,"Utawala, house 4",-1.2921,36.8219
                "Otieno ""Oti"" Ouma",+254733000111,,,,,
                """;
        final Api.Response response = api.postCsv("/api/v1/customer-imports?routerId=" + routerId, csv);
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        assertThat(response.header("Cache-Control")).contains("no-store");

        final JsonNode result = response.json();
        assertThat(result.get("imported").asInt()).isEqualTo(2);
        final JsonNode first = result.get("rows").get(0);
        assertThat(first.get("number").asString()).isEqualTo(tenant.code() + "000001");
        assertThat(first.get("pppoeUsername").asString()).isEqualTo(tenant.code() + "000001");
        assertThat(first.get("pppoePassword").asString()).hasSize(16);
        final JsonNode second = result.get("rows").get(1);
        assertThat(second.get("name").asString()).isEqualTo("Otieno \"Oti\" Ouma");
        assertThat(second.get("pppoeUsername").isNull()).isTrue();

        final JsonNode stored = api.get("/api/v1/customers/" + first.get("customerId").asString()).json();
        assertThat(stored.get("phone").asString()).isEqualTo("+254712345678");
        assertThat(stored.get("installationLocation").asString()).isEqualTo("Utawala, house 4");
        assertThat(api.get("/api/v1/subscriptions").json().get("total").asLong()).isOne();
    }

    @Test
    void anyInvalidRowRejectsTheWholeFileWithEveryRowError() {
        Catalog.customer(api, "Existing", "0799000000");
        final String csv =
                """
                name,phone,package
                Good Row,0711000001,Home 10
                ,0711000002,
                Bad Phone,12345,
                Duplicate,0711000001,
                Taken,0799 000 000,
                Unknown Package,0711000003,Fibre 100
                """;
        final Api.Response response = api.postCsv("/api/v1/customer-imports?routerId=" + routerId, csv);
        assertThat(response.status()).isEqualTo(400);
        assertThat(response.body())
                .contains("{\"row\":2,\"field\":\"name\"")
                .contains("{\"row\":3,\"field\":\"phone\",\"message\":\"not a valid phone number\"}")
                .contains("{\"row\":4,\"field\":\"phone\",\"message\":\"same number as row 1\"}")
                .contains("{\"row\":5,\"field\":\"phone\",\"message\":\"already belongs to a customer\"}")
                .contains("{\"row\":6,\"field\":\"package\"");
        // Nothing was imported: only the pre-existing customer, and no lines.
        assertThat(api.get("/api/v1/customers").json().get("total").asLong()).isOne();
        assertThat(api.get("/api/v1/subscriptions").json().get("total").asLong()).isZero();
    }

    @Test
    void rowsWithAPackageNeedARouter() {
        final Api.Response response = api.postCsv("/api/v1/customer-imports", "name,phone,package\nA,0711000001,Home 10\n");
        assertThat(response.status()).isEqualTo(400);
        assertThat(response.body()).contains("\"field\":\"routerId\"");
    }

    @Test
    void headerMustNameKnownColumnsIncludingNameAndPhone() {
        final Api.Response response = api.postCsv("/api/v1/customer-imports", "fullname,mobile\nA,0711000001\n");
        assertThat(response.status()).isEqualTo(400);
        assertThat(response.body())
                .contains("{\"row\":0,\"field\":\"fullname\"")
                .contains("{\"row\":0,\"field\":\"name\",\"message\":\"column is required\"}");
    }

    @Test
    void emptyFileIs400() {
        assertThat(api.postCsv("/api/v1/customer-imports", "").status()).isEqualTo(400);
        assertThat(api.postCsv("/api/v1/customer-imports", "name,phone\n").status()).isEqualTo(400);
    }

    // The cutover import (ADR 0014, S11): a preview first, then lines activated with the expiry the
    // customer already paid for, kept credentials, old references; nothing paid on the platform.

    @Test
    void S11_previewThenImportActivatesLinesWithTheirPaidExpiryAndKeepsCredentials() {
        openCutoverWindow(7);
        final ZoneId zone = zone();
        final LocalDate today = LocalDate.now(zone);
        final Instant future = today.plusDays(10).atTime(LocalTime.of(9, 30)).atZone(zone).toInstant();
        final Instant past = today.minusDays(5).atTime(LocalTime.of(9, 30)).atZone(zone).toInstant();
        final String csv = """
                name,phone,package,paid_until,pppoe_username,pppoe_password,legacy_account_ref
                Kept Creds,0711000001,Home 10,%s,old.user@isp,Old-Secret-1,hse-4
                Expired One,0711000002,Home 10,%s,,,12
                Fresh Line,0711000003,Home 10,,,,
                No Line,0711000004,,,,,
                """.formatted(today.plusDays(10), today.minusDays(5));
        final String query = "?routerId=" + routerId + "&timeOfDay=09:30";

        final long minutesAhead = Duration.between(Instant.now(), future).toMinutes();
        final Api.Response preview = api.postCsv("/api/v1/customer-imports/preview" + query, csv);
        assertThat(preview.status()).as(preview.body()).isEqualTo(200);
        final JsonNode summary = preview.json();
        assertThat(summary.get("rows").asInt()).isEqualTo(4);
        assertThat(summary.get("lines").asInt()).isEqualTo(3);
        assertThat(summary.get("credentialsKept").asInt()).isEqualTo(1);
        assertThat(summary.get("legacyRefs").asInt()).isEqualTo(2);
        final JsonNode activation = summary.get("activation");
        assertThat(activation.get("total").asInt()).isEqualTo(2);
        assertThat(activation.get("expired").asInt()).isEqualTo(1);
        assertThat(activation.get("future").asInt()).isEqualTo(1);
        assertThat(Instant.parse(activation.get("earliestPaidUntil").asString())).isEqualTo(past);
        assertThat(Instant.parse(activation.get("latestPaidUntil").asString())).isEqualTo(future);
        // The time still ahead, of thirty days of a KES 2,500 package, rounded up to the shilling (B6);
        // the server's clock may be a minute past the test's.
        assertThat(activation.get("valueGranted").get("amount").asLong())
                .isIn(proRata(minutesAhead + 1), proRata(minutesAhead), proRata(minutesAhead - 1), proRata(minutesAhead - 2));
        assertThat(codes(summary.get("warnings")))
                .contains("PAST_SHARE", "SHORT_LEGACY_REFS", "ACTIVE_WITHOUT_CREDENTIALS")
                .doesNotContain("DAY_CLUSTER");
        final String confirmation = summary.get("confirmation").asString();
        assertThat(confirmation).hasSize(64);
        assertThat(preview.body()).doesNotContain("Old-Secret-1");
        assertThat(api.get("/api/v1/customers").json().get("total").asLong()).as("a preview writes nothing").isZero();

        final Api.Response refused = api.postCsv("/api/v1/customer-imports" + query, csv);
        assertThat(refused.status()).isEqualTo(400);
        assertThat(refused.body()).contains("\"field\":\"confirmation\"");
        assertThat(api.postCsv("/api/v1/customer-imports" + query + "&confirmation=" + confirmation, csv.replace("Fresh", "Other"))
                .status()).as("another file needs its own preview").isEqualTo(400);

        final Api.Response response = api.postCsv("/api/v1/customer-imports" + query + "&confirmation=" + confirmation, csv);
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        assertThat(response.body()).doesNotContain("Old-Secret-1");
        final JsonNode result = response.json();
        assertThat(result.get("imported").asInt()).isEqualTo(4);
        assertThat(result.get("activated").asInt()).isEqualTo(2);
        final String batchId = result.get("batchId").asString();

        final JsonNode kept = result.get("rows").get(0);
        assertThat(kept.get("pppoeUsername").asString()).isEqualTo("old.user@isp");
        assertThat(kept.get("credentialKept").asBoolean()).isTrue();
        assertThat(kept.get("pppoePassword").isNull()).as("a kept password is never echoed").isTrue();
        assertThat(Instant.parse(kept.get("activatedUntil").asString())).isEqualTo(future);
        final JsonNode keptLine = api.get("/api/v1/subscriptions/" + kept.get("subscriptionId").asString()).json();
        assertThat(keptLine.get("state").asString()).isEqualTo("ACTIVE");
        assertThat(keptLine.get("usernameKept").asBoolean()).isTrue();
        assertThat(keptLine.get("accessProfile").asString()).isEqualTo("FULL");
        assertThat(Instant.parse(keptLine.get("expiresAt").asString())).isEqualTo(future);
        assertThat(Instant.parse(keptLine.get("usage").get("cycleStartedAt").asString()))
                .as("the cycle began one package duration before the paid expiry (N9)").isEqualTo(future.minus(Duration.ofDays(30)));
        assertThat(api.get("/api/v1/customers/" + kept.get("customerId").asString()).json().get("legacyAccountRef").asString())
                .isEqualTo("HSE-4");

        final JsonNode expired = result.get("rows").get(1);
        assertThat(expired.get("credentialKept").asBoolean()).isFalse();
        assertThat(expired.get("pppoePassword").asString()).hasSize(16);
        final JsonNode expiredLine = api.get("/api/v1/subscriptions/" + expired.get("subscriptionId").asString()).json();
        assertThat(expiredLine.get("state").asString()).isEqualTo("ACTIVE");
        assertThat(expiredLine.get("accessProfile").asString()).as("expired: the pay page, not a rejection (S2)").isEqualTo("RESTRICTED");

        final JsonNode fresh = result.get("rows").get(2);
        assertThat(fresh.get("activatedUntil").isNull()).isTrue();
        assertThat(api.get("/api/v1/subscriptions/" + fresh.get("subscriptionId").asString()).json().get("state").asString())
                .isEqualTo("PENDING");

        // The RADIUS view answers for the kept name through the tenant's router (T3), with the kept password.
        final String nas = api.get("/api/v1/routers/" + routerId).json().get("tunnelIp").asString();
        assertThat(OwnerSql.client().sql("""
                        select value from v_radius_auth_v7
                        where tenant_id = :t and username = 'old.user@isp' and nasname = :nas and attribute = 'Cleartext-Password'
                        """).param("t", tenant.id()).param("nas", nas).query(String.class).single()).isEqualTo("Old-Secret-1");

        // No money moved (B1): no ledger entry; the audit trail has the batch, each activation, and never the password.
        assertThat(OwnerSql.client().sql("select count(*) from billing_ledger_entry where tenant_id = :t")
                .param("t", tenant.id()).query(Long.class).single()).isZero();
        assertThat(OwnerSql.client().sql("select count(*) from identity_audit_log where tenant_id = :t and action = 'subscriptions.activated_by_import'")
                .param("t", tenant.id()).query(Long.class).single()).isEqualTo(2);
        final String batchAudit = OwnerSql.client().sql(
                        "select after_state::text from identity_audit_log where tenant_id = :t and action = 'subscriptions.customers_imported'")
                .param("t", tenant.id()).query(String.class).single();
        assertThat(batchAudit).contains("\"activated\": 2").contains("\"credentialsKept\": 1").contains("PAST_SHARE");
        assertThat(OwnerSql.client().sql("select string_agg(coalesce(after_state::text, '') || coalesce(before_state::text, ''), ' ') from identity_audit_log where tenant_id = :t")
                .param("t", tenant.id()).query(String.class).single()).doesNotContain("Old-Secret-1");
        assertThat(OwnerSql.client().sql("select count(*) from outbox_event where tenant_id = :t and event_type = 'SubscriptionStateChanged' and payload::text like '%IMPORTED%'")
                .param("t", tenant.id()).query(Long.class).single()).isEqualTo(2);

        final JsonNode batch = api.get("/api/v1/customer-imports/" + batchId).json();
        assertThat(batch.get("rows").asInt()).isEqualTo(4);
        assertThat(batch.get("activation").get("total").asInt()).isEqualTo(2);
        assertThat(codes(batch.get("warnings"))).contains("PAST_SHARE");
    }

    @Test
    void S11_theWindowThePermissionTheCapAndTheDateShapeGuardTheActivation() {
        final LocalDate today = LocalDate.now(zone());
        final String csv = "name,phone,package,paid_until\nA,0711000001,Home 10," + today.plusDays(3) + "\n";

        final Api.Response closed = api.postCsv("/api/v1/customer-imports/preview?routerId=" + routerId, csv);
        assertThat(closed.status()).isEqualTo(400);
        assertThat(closed.body()).contains("\"field\":\"paid_until\"").contains("cutover window is closed");

        openCutoverWindow(7);
        final Api staff = new Api(port, tenant.id(), "customers.write", "subscriptions.write");
        final Api.Response noPermission = staff.postCsv("/api/v1/customer-imports/preview?routerId=" + routerId, csv);
        assertThat(noPermission.status()).isEqualTo(400);
        assertThat(noPermission.body()).contains("subscriptions.import_active");
        assertThat(api.postCsv("/api/v1/customer-imports/preview?routerId=" + routerId, csv).status()).isEqualTo(200);

        final Api.Response tooFar = api.postCsv("/api/v1/customer-imports/preview?routerId=" + routerId,
                "name,phone,package,paid_until\nA,0711000001,Home 10," + today.plusDays(31) + "\n");
        assertThat(tooFar.status()).isEqualTo(400);
        assertThat(tooFar.body()).contains("{\"row\":1,\"field\":\"paid_until\",\"message\":\"more than one package duration ahead");

        final Api.Response badShapes = api.postCsv("/api/v1/customer-imports/preview?routerId=" + routerId, """
                name,phone,package,paid_until
                Slash,0711000001,Home 10,15/11/2026
                No Package,0711000002,,2026-11-15
                """);
        assertThat(badShapes.status()).isEqualTo(400);
        assertThat(badShapes.body())
                .contains("{\"row\":1,\"field\":\"paid_until\",\"message\":\"not a date")
                .contains("{\"row\":2,\"field\":\"paid_until\",\"message\":\"needs a package\"}");

        openCutoverWindow(-1);
        assertThat(api.postCsv("/api/v1/customer-imports/preview?routerId=" + routerId, csv).body())
                .as("the window closes by itself").contains("cutover window is closed");
    }

    @Test
    void T2_T3_keptCredentialsAreUniquePerTenantNeverInTheGeneratedShapeAndNeverEchoed() {
        final Api.Response rejected = api.postCsv("/api/v1/customer-imports?routerId=" + routerId, """
                name,phone,package,pppoe_username,pppoe_password
                Generated Shape,0711000001,Home 10,CB000009,Hidden-Pw-1
                Password Only,0711000002,Home 10,,Hidden-Pw-2
                Bad Chars,0711000003,Home 10,"bad name",Hidden-Pw-3
                First,0711000004,Home 10,twin,Hidden-Pw-4
                Second,0711000005,Home 10,twin,Hidden-Pw-5
                No Package,0711000006,,alone,Hidden-Pw-6
                """);
        assertThat(rejected.status()).isEqualTo(400);
        assertThat(rejected.body())
                .contains("{\"row\":1,\"field\":\"pppoe_username\",\"message\":\"looks like a platform-generated username")
                .contains("{\"row\":2,\"field\":\"pppoe_username\",\"message\":\"pppoe_username and pppoe_password go together\"}")
                .contains("{\"row\":3,\"field\":\"pppoe_username\",\"message\":\"1 to 64 letters, digits or @ . _ -\"}")
                .contains("{\"row\":5,\"field\":\"pppoe_username\",\"message\":\"same username as row 4\"}")
                .contains("{\"row\":6,\"field\":\"pppoe_username\",\"message\":\"needs a package\"}")
                .doesNotContain("Hidden-Pw");

        final String csv = "name,phone,package,pppoe_username,pppoe_password\nLegacy,0711000001,Home 10,legacy-1,Kept-Pw-1\n";
        final Api.Response imported = api.postCsv("/api/v1/customer-imports?routerId=" + routerId, csv);
        assertThat(imported.status()).as(imported.body()).isEqualTo(200);
        assertThat(imported.json().get("rows").get(0).get("pppoeUsername").asString()).isEqualTo("legacy-1");
        final Api.Response again = api.postCsv("/api/v1/customer-imports?routerId=" + routerId, csv.replace("0711000001", "0711000009"));
        assertThat(again.status()).isEqualTo(400);
        assertThat(again.body()).contains("{\"row\":1,\"field\":\"pppoe_username\",\"message\":\"already used by a line\"}");

        // Another tenant may keep the same name: FreeRADIUS resolves a username with the asking router's tenant (T3).
        final TestTenants.Created other = TestTenants.createWithCode(jdbc, tx);
        final Api otherApi = new Api(port, other.id());
        Catalog.pppoePackage(otherApi, "Home 10");
        final String otherRouter = Catalog.router(otherApi, "Other-01").json().get("router").get("id").asString();
        final Api.Response elsewhere = otherApi.postCsv("/api/v1/customer-imports?routerId=" + otherRouter, csv);
        assertThat(elsewhere.status()).as(elsewhere.body()).isEqualTo(200);
        assertThat(OwnerSql.client().sql("select count(distinct tenant_id) from subscriptions_subscription where pppoe_username = 'legacy-1'")
                .query(Long.class).single()).isEqualTo(2);
    }

    @Test
    void T2_aKeptUsernameIsCorrectedWhileTheWindowIsOpenAGeneratedOneNever() {
        final Api.Response imported = api.postCsv("/api/v1/customer-imports?routerId=" + routerId, """
                name,phone,package,pppoe_username,pppoe_password
                Typo,0711000001,Home 10,jhon.doe,Kept-Pw-1
                Plain,0711000002,Home 10,,
                """);
        assertThat(imported.status()).as(imported.body()).isEqualTo(200);
        final JsonNode result = imported.json();
        final String keptLine = result.get("rows").get(0).get("subscriptionId").asString();
        final String generatedLine = result.get("rows").get(1).get("subscriptionId").asString();
        final String body = "{\"username\":\"john.doe\"}";

        assertThat(api.put("/api/v1/subscriptions/" + keptLine + "/pppoe-username", body).status())
                .as("the window is closed").isEqualTo(409);
        openCutoverWindow(7);
        assertThat(new Api(port, tenant.id(), "subscriptions.write").put("/api/v1/subscriptions/" + keptLine + "/pppoe-username", body)
                .status()).as("needs the Owner's permission").isEqualTo(403);
        final Api.Response corrected = api.put("/api/v1/subscriptions/" + keptLine + "/pppoe-username", body);
        assertThat(corrected.status()).as(corrected.body()).isEqualTo(200);
        assertThat(corrected.json().get("pppoeUsername").asString()).isEqualTo("john.doe");
        assertThat(api.put("/api/v1/subscriptions/" + generatedLine + "/pppoe-username", body).status()).isEqualTo(409);
        assertThat(api.put("/api/v1/subscriptions/" + keptLine + "/pppoe-username", "{\"username\":\"CB000001\"}").status())
                .isEqualTo(400);
        assertThat(OwnerSql.client().sql("select count(*) from identity_audit_log where tenant_id = :t and action = 'subscriptions.pppoe_username_corrected'")
                .param("t", tenant.id()).query(Long.class).single()).isOne();
    }

    @Test
    void P3_oldReferencesAreUpperCasedUniqueInTheFileAndAgainstCustomers() {
        final Api.Response rejected = api.postCsv("/api/v1/customer-imports", """
                name,phone,legacy_account_ref
                A,0711000001,hse-4
                B,0711000002,HSE-4
                C,0711000003,no!way
                """);
        assertThat(rejected.status()).isEqualTo(400);
        assertThat(rejected.body())
                .contains("{\"row\":2,\"field\":\"legacy_account_ref\",\"message\":\"same reference as row 1\"}")
                .contains("{\"row\":3,\"field\":\"legacy_account_ref\"");
        final JsonNode imported = api.postCsv("/api/v1/customer-imports", "name,phone,legacy_account_ref\nA,0711000001, hse-4 \n").json();
        assertThat(api.get("/api/v1/customers/" + imported.get("rows").get(0).get("customerId").asString()).json()
                .get("legacyAccountRef").asString()).isEqualTo("HSE-4");
        final Api.Response again = api.postCsv("/api/v1/customer-imports", "name,phone,legacy_account_ref\nB,0711000002,Hse-4\n");
        assertThat(again.status()).isEqualTo(400);
        assertThat(again.body()).contains("{\"row\":1,\"field\":\"legacy_account_ref\",\"message\":\"already belongs to a customer\"}");
    }

    @Test
    void S11_aFileWhoseDatesAllFallEarlyInTheMonthIsWarnedAboutASwappedFormat() {
        openCutoverWindow(7);
        final LocalDate today = LocalDate.now(zone());
        // Ten rows dated within the next twelve days: enough rows, every day of month at most 12 only
        // when today is late in a month, so pick the dates explicitly on the next month's days 1 to 10.
        final LocalDate firstOfNext = today.plusMonths(1).withDayOfMonth(1);
        final StringBuilder csv = new StringBuilder("name,phone,package,paid_until\n");
        for (int i = 0; i < 10; i++) {
            final LocalDate date = firstOfNext.plusDays(i);
            // Beyond the 30-day cap the row is rejected, so stay within it: fall back to the next days when needed.
            csv.append("C").append(i).append(",07110000").append(10 + i).append(",Home 10,")
                    .append(date.isAfter(today.plusDays(29)) ? today.plusDays(1 + i) : date).append('\n');
        }
        final Api.Response preview = api.postCsv("/api/v1/customer-imports/preview?routerId=" + routerId, csv.toString());
        assertThat(preview.status()).as(preview.body()).isEqualTo(200);
        final boolean allEarly = preview.json().get("activation").get("total").asInt() == 10
                && firstOfNext.plusDays(9).isBefore(today.plusDays(30));
        if (allEarly) {
            assertThat(codes(preview.json().get("warnings"))).contains("DAY_CLUSTER");
        }
    }

    private void openCutoverWindow(final int days) {
        OwnerSql.client().sql("update tenancy_tenant set cutover_window_until = now() + make_interval(days => :d) where id = :t")
                .param("d", days).param("t", tenant.id()).update();
    }

    private ZoneId zone() {
        return ZoneId.of(OwnerSql.client().sql("select time_zone from tenancy_tenant where id = :t")
                .param("t", tenant.id()).query(String.class).single());
    }

    /** KES 2,500 for 43,200 minutes, {@code minutes} of them, rounded up to the shilling, in cents. */
    private static long proRata(final long minutes) {
        return (long) Math.ceil(250_000L * minutes / 43_200.0 / 100.0) * 100;
    }

    private static List<String> codes(final JsonNode warnings) {
        final List<String> codes = new ArrayList<>();
        warnings.forEach(w -> codes.add(w.get("code").asString()));
        return codes;
    }
}
