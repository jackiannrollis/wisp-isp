package com.corebalt.isp.notifications.application;

import com.corebalt.isp.notifications.api.OneTimeCodes;
import com.corebalt.isp.notifications.domain.SendOutcome;
import com.corebalt.isp.notifications.infrastructure.AfricasTalkingClient;
import com.corebalt.isp.shared.id.UuidV7;
import com.corebalt.isp.shared.tenant.TenantContext;
import com.corebalt.isp.tenancy.api.TenantRegistry;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** {@link OneTimeCodes} over the tenant's Africa's Talking account; the logged body has the code masked. */
@Service
class OneTimeCodeSender implements OneTimeCodes {

    static final String TEMPLATE_KEY = "SIGN_IN_CODE";
    /** Fixed wording, not tenant-editable: with the staff password below, the only messages that carry a secret. */
    static final String BODY = "%s is your %s sign-in code. It expires in 5 minutes. Never share it with anyone.";
    static final String STAFF_PASSWORD_KEY = "STAFF_TEMPORARY_PASSWORD";
    /** One GSM-7 part with a 14-character password and a 30-character brand. */
    static final String STAFF_PASSWORD_BODY =
            "%s staff sign-in: temporary password %s, valid 72 hours. You must change it when you sign in.";

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final SmsSettings settings;
    private final AfricasTalkingClient provider;
    private final TenantRegistry tenants;
    private final Clock clock;

    OneTimeCodeSender(
            final JdbcClient jdbc,
            final TransactionTemplate tx,
            final SmsSettings settings,
            final AfricasTalkingClient provider,
            final TenantRegistry tenants,
            final Clock clock) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.settings = settings;
        this.provider = provider;
        this.tenants = tenants;
        this.clock = clock;
    }

    @Override
    public Delivery sendSignInCode(final UUID customerId, final String e164, final String code) {
        final String brand = brand();
        return send(customerId, e164, TEMPLATE_KEY, BODY.formatted(code, brand), BODY.formatted("******", brand));
    }

    @Override
    public Delivery sendTemporaryPassword(final String e164, final String password) {
        final String brand = brand();
        return send(null, e164, STAFF_PASSWORD_KEY, STAFF_PASSWORD_BODY.formatted(brand, password),
                STAFF_PASSWORD_BODY.formatted(brand, "**************"));
    }

    private String brand() {
        return tenants.find(TenantContext.requireTenantId()).map(TenantRegistry.TenantRef::name).orElse("Corebalt");
    }

    /** {@code body} goes to the provider only; the log keeps {@code masked}. */
    private Delivery send(final UUID customerId, final String e164, final String template, final String body,
                          final String masked) {
        final Optional<SmsSettings.Account> account = tx.execute(s -> settings.account());
        if (account.isEmpty()) {
            log(customerId, e164, template, masked, "SUPPRESSED", "SMS is not configured for this tenant", null);
            return Delivery.NOT_CONFIGURED;
        }
        SendOutcome outcome;
        try {
            final AfricasTalkingClient.Result result = provider.send(account.get().sandbox(), account.get().username(),
                    account.get().apiKey(), account.get().senderId(), e164, body);
            outcome = SendOutcome.ofRecipient(result.statusCode(), result.status(), result.messageId());
        } catch (AfricasTalkingClient.TransportFailure e) {
            outcome = SendOutcome.ofTransport(e.httpStatus(), e.getMessage());
        }
        if (outcome instanceof SendOutcome.Sent sent) {
            log(customerId, e164, template, masked, "SENT", null, sent.messageId());
            return Delivery.SENT;
        }
        log(customerId, e164, template, masked, "FAILED", reason(outcome), null);
        return Delivery.FAILED;
    }

    private static String reason(final SendOutcome outcome) {
        return switch (outcome) {
            case SendOutcome.Permanent p -> p.reason();
            case SendOutcome.TenantProblem p -> p.reason();
            case SendOutcome.Transient t -> t.reason();
            case SendOutcome.Sent s -> null;
        };
    }

    private void log(final UUID customerId, final String e164, final String template, final String masked,
                     final String status, final String reason, final String messageId) {
        final Instant now = clock.instant();
        tx.executeWithoutResult(s -> jdbc.sql("""
                        insert into notifications_notification
                            (id, tenant_id, customer_id, recipient, template_key, body, dedupe_key, status, scheduled_for,
                             next_attempt_at, attempts, provider_message_id, failure_reason, sent_at, created_at, updated_at)
                        values (:id, :tenantId, :customerId, :recipient, :template, :body, :dedupe, :status, :now,
                                :now, 1, :messageId, :reason, :sentAt, :now, :now)
                        """)
                .param("id", UuidV7.next(clock))
                .param("tenantId", TenantContext.requireTenantId())
                .param("customerId", customerId)
                .param("recipient", e164)
                .param("template", template)
                .param("body", masked)
                .param("dedupe", template.toLowerCase() + ":" + UUID.randomUUID())
                .param("status", status)
                .param("messageId", messageId)
                .param("reason", reason)
                .param("sentAt", "SENT".equals(status) ? Timestamp.from(now) : null)
                .param("now", Timestamp.from(now))
                .update());
    }
}
