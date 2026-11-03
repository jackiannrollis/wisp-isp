package com.corebalt.isp.tenancy;

import static org.assertj.core.api.Assertions.assertThat;

import com.corebalt.isp.billing.application.WalletService;
import com.corebalt.isp.shared.money.Money;
import com.corebalt.isp.shared.tenant.TenantContext;
import com.corebalt.isp.subscriptions.api.RenewalResultDto;
import com.corebalt.isp.subscriptions.application.RenewalService;
import com.corebalt.isp.support.Api;
import com.corebalt.isp.support.Catalog;
import com.corebalt.isp.support.IntegrationTest;
import com.corebalt.isp.support.OwnerSql;
import com.corebalt.isp.support.TestTenants;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * The account's own settings (settings.write, Owner only; decisions.md 2026-10-21): grace policy,
 * partial-payment rule, pause limit and branding, audited (A1); a grace change leaves a cycle already
 * paid for alone (S6); the SMS sender ID and window have their own endpoint.
 */
class AccountSettingsIT extends IntegrationTest {

    private static final String SETTINGS = """
            {"name":"Kahawa Fibre","brandColor":"#1D4ED8","logoUrl":"https://example.test/logo.png","defaultGraceDays":3,
             "graceMode":"THROTTLED","partialPaymentRule":"PRO_RATA_DAYS","maxPauseDays":14}""";

    @Value("${local.server.port}")
    int port;

    @Autowired JdbcClient jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired RenewalService renewals;
    @Autowired WalletService wallets;

    UUID tenant;
    Api owner;

    @BeforeEach
    void tenant() {
        tenant = TestTenants.create(jdbc, tx);
        owner = new Api(port, tenant);
    }

    @Test
    void A2_onlySettingsWriteMayReadOrChangeThem() {
        assertThat(new Api(port, tenant, "settings.secrets", "staff.manage").get("/api/v1/settings/account").status())
                .isEqualTo(403);
        assertThat(new Api(port, tenant, "settings.secrets").put("/api/v1/settings/account", SETTINGS).status()).isEqualTo(403);
        assertThat(owner.get("/api/v1/settings/account").status()).isEqualTo(200);
    }

    @Test
    void A1_aChangeIsSavedAuditedAndTheBrandingFollowsAtOnce() {
        final JsonNode saved = owner.put("/api/v1/settings/account", SETTINGS).json();
        assertThat(saved.get("name").asString()).isEqualTo("Kahawa Fibre");
        assertThat(saved.get("brandColor").asString()).isEqualTo("#1d4ed8");
        assertThat(saved.get("graceMode").asString()).isEqualTo("THROTTLED");
        assertThat(saved.get("maxPauseDays").asInt()).isEqualTo(14);
        assertThat(saved.get("code").asString()).as("fixed at creation").isNotBlank();

        final JsonNode branding = owner.get("/api/v1/branding").json();
        assertThat(branding.get("name").asString()).isEqualTo("Kahawa Fibre");
        assertThat(branding.get("primaryColor").asString()).isEqualTo("#1d4ed8");
        assertThat(branding.get("logoUrl").asString()).isEqualTo("https://example.test/logo.png");

        final JsonNode audit = owner.get("/api/v1/audit-log?action=tenancy.").json().get("items").get(0);
        assertThat(audit.get("action").asString()).isEqualTo("tenancy.settings_changed");
        assertThat(audit.get("after").asString()).contains("\"graceMode\": \"THROTTLED\"").contains("\"maxPauseDays\": 14");

        // Saving the same values again changes nothing, so it audits nothing.
        owner.put("/api/v1/settings/account", SETTINGS);
        assertThat(owner.get("/api/v1/audit-log?action=tenancy.").json().get("total").asLong()).isOne();
    }

    @Test
    void invalidValuesAreRefusedField_byField() {
        final Api.Response bad = owner.put("/api/v1/settings/account", SETTINGS
                .replace("#1D4ED8", "blue").replace("https://example.test/logo.png", "http://example.test/logo.png")
                .replace("\"maxPauseDays\":14", "\"maxPauseDays\":400"));
        assertThat(bad.status()).isEqualTo(400);
        assertThat(bad.body()).contains("brandColor").contains("logoUrl").contains("maxPauseDays");
    }

    @Test
    void A2_settingsWriteStaysWithTheOwnerRole() {
        final Api.Response refused = owner.post("/api/v1/roles", """
                {"name":"Office manager","permissions":["customers.write","settings.write"]}""");
        assertThat(refused.status()).isEqualTo(400);
        assertThat(refused.body()).contains("Owner role only");
    }

    @Test
    void S6_aGraceModeChangeAppliesFromEachLinesNextRenewal() {
        final String packageId = Catalog.pppoePackage(owner, "Home 10");
        final JsonNode router = Catalog.router(owner, "Ruaka-01").json().get("router");
        final UUID customer = UUID.fromString(Catalog.customer(owner, "Amina Hassan", "0722000444"));
        final UUID line = UUID.fromString(owner.post("/api/v1/subscriptions", "{\"customerId\":\"" + customer
                + "\",\"packageId\":\"" + packageId + "\",\"routerId\":\"" + router.get("id").asString() + "\"}")
                .json().get("subscription").get("id").asString());
        fund(customer, 250_000);
        assertThat(TenantContext.callAsTenant(tenant, () -> renewals.renew(line)).outcome()).isEqualTo(RenewalResultDto.Outcome.RENEWED);

        // Sold under FULL_SPEED grace; the tenant then switches to THROTTLED grace.
        owner.put("/api/v1/settings/account", SETTINGS);
        OwnerSql.client().sql("update subscriptions_subscription set expires_at = now() - interval '1 hour', grace_days = 3 where id = :id")
                .param("id", line).update();
        assertThat(owner.get("/api/v1/subscriptions/" + line).json().get("accessProfile").asString())
                .as("the paid cycle keeps the grace it was sold with").isEqualTo("FULL");

        fund(customer, 250_000);
        assertThat(TenantContext.callAsTenant(tenant, () -> renewals.renew(line)).outcome()).isEqualTo(RenewalResultDto.Outcome.RENEWED);
        assertThat(OwnerSql.client().sql("select grace_mode from subscriptions_subscription where id = :id")
                .param("id", line).query(String.class).single()).isEqualTo("THROTTLED");
    }

    @Test
    void M2_theSenderAndWindowNeedAnSmsAccountFirst() {
        assertThat(owner.put("/api/v1/settings/notifications/sending",
                "{\"senderId\":\"KAHAWA\",\"windowStart\":\"08:00\",\"windowEnd\":\"20:00\"}").status()).isEqualTo(404);
        final Api keys = new Api(port, tenant, "settings.secrets", "settings.notifications");
        assertThat(keys.put("/api/v1/settings/notifications/sending",
                "{\"senderId\":\"KAHAWA\",\"windowStart\":\"08:00\",\"windowEnd\":\"20:00\"}").status()).isEqualTo(403);

        // The account is set up with the keys; from then on the sender ID and window are the Owner's.
        assertThat(keys.put("/api/v1/settings/notifications", """
                {"partnerId":"fake-partner-id","senderId":"FIRST","windowStart":"07:00","windowEnd":"21:00",
                 "apiKey":"fake-at-key-for-tests"}""").status()).isEqualTo(200);
        final JsonNode kept = keys.put("/api/v1/settings/notifications", """
                {"partnerId":"fake-partner-id","senderId":"OTHER","windowStart":"06:00","windowEnd":"23:00"}""").json();
        assertThat(kept.get("senderId").asString()).isEqualTo("FIRST");
        assertThat(kept.get("windowStart").asString()).isEqualTo("07:00");

        final JsonNode changed = owner.put("/api/v1/settings/notifications/sending",
                "{\"senderId\":\"KAHAWA\",\"windowStart\":\"08:00\",\"windowEnd\":\"20:00\"}").json();
        assertThat(changed.get("senderId").asString()).isEqualTo("KAHAWA");
        assertThat(changed.get("windowEnd").asString()).isEqualTo("20:00");
    }

    private void fund(final UUID customer, final long minor) {
        TenantContext.runAsTenant(tenant, () -> tx.executeWithoutResult(
                s -> wallets.adjust(customer, Money.ofMinor(minor, "KES"), "test funding")));
    }
}
