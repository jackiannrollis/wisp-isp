package com.corebalt.isp.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import com.corebalt.isp.notifications.api.OneTimeCodes;
import com.corebalt.isp.notifications.application.SmsSender;
import com.corebalt.isp.shared.tenant.TenantContext;
import com.corebalt.isp.support.Api;
import com.corebalt.isp.support.FakeAfricasTalking;
import com.corebalt.isp.support.IntegrationTest;
import com.corebalt.isp.support.OwnerSql;
import com.corebalt.isp.support.TestTenants;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Staging's SMS allowlist (decisions.md 2026-10-21): with isp.sms.allowlist set, only listed numbers
 * get an SMS; everything else is kept as SUPPRESSED with the reason and never reaches the provider,
 * sign-in codes included. Numbers and keys are fakes.
 */
@TestPropertySource(properties = "isp.sms.allowlist=+254799000001, +254799000002")
class SmsAllowlistIT extends IntegrationTest {

    static final String LISTED = "+254799000001";
    static final String OTHER = "+254799000777";

    @Value("${local.server.port}")
    int port;

    @Autowired JdbcClient jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired SmsSender sender;
    @Autowired OneTimeCodes codes;

    UUID tenant;

    @BeforeEach
    void tenantWithSms() {
        tenant = TestTenants.create(jdbc, tx);
        AT.registerSender("LABISP");
        final Api.Response settings = new Api(port, tenant).put("/api/v1/settings/notifications", """
                {"atUsername":"sandbox","senderId":"LABISP","sandbox":true,"apiKey":"%s","windowStart":"00:00","windowEnd":"23:59"}
                """.formatted(FakeAfricasTalking.API_KEY));
        assertThat(settings.status()).as(settings.body()).isEqualTo(200);
    }

    @Test
    void M2_onlyListedNumbersAreSentTo_theRestAreSuppressedWithTheReason() {
        queue(LISTED, "allowlist-listed");
        queue(OTHER, "allowlist-other");

        TenantContext.runAsTenant(tenant, sender::sendDue);

        assertThat(message("allowlist-listed")).containsEntry("status", "SENT");
        assertThat(message("allowlist-other")).containsEntry("status", "SUPPRESSED")
                .containsEntry("failure_reason", "Not on this environment's SMS allowlist");
        assertThat(AT.requestsTo(OTHER)).isEmpty();
        assertThat(AT.requestsTo(LISTED)).hasSize(1);
    }

    @Test
    void M3_aSignInCodeToAnUnlistedNumberIsNeverSent() {
        assertThat(TenantContext.callAsTenant(tenant, () -> codes.sendSignInCode(null, OTHER, "123456")))
                .isEqualTo(OneTimeCodes.Delivery.NOT_CONFIGURED);
        assertThat(AT.requestsTo(OTHER)).isEmpty();
        final Map<String, Object> logged = OwnerSql.client().sql("""
                        select status, body, failure_reason from notifications_notification
                        where tenant_id = :t and recipient = :r
                        """)
                .param("t", tenant).param("r", OTHER).query().singleRow();
        assertThat(logged).containsEntry("status", "SUPPRESSED");
        assertThat((String) logged.get("body")).doesNotContain("123456");
    }

    private void queue(final String recipient, final String dedupe) {
        OwnerSql.client().sql("""
                        insert into notifications_notification
                            (id, tenant_id, recipient, template_key, body, dedupe_key, status, scheduled_for,
                             next_attempt_at, created_at, updated_at)
                        values (gen_random_uuid(), :t, :r, 'PAYMENT_RECEIVED', 'Test message', :d, 'QUEUED', now(),
                                now(), now(), now())
                        """)
                .param("t", tenant).param("r", recipient).param("d", dedupe).update();
    }

    private Map<String, Object> message(final String dedupe) {
        return OwnerSql.client().sql("""
                        select status, failure_reason from notifications_notification where tenant_id = :t and dedupe_key = :d
                        """)
                .param("t", tenant).param("d", dedupe).query().singleRow();
    }
}
