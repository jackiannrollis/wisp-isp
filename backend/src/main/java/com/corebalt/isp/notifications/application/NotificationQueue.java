package com.corebalt.isp.notifications.application;

import com.corebalt.isp.notifications.domain.SmsSegments;
import com.corebalt.isp.notifications.domain.Template;
import com.corebalt.isp.shared.id.UuidV7;
import com.corebalt.isp.shared.tenant.TenantContext;
import com.corebalt.isp.shared.web.InvalidRequestException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Renders and queues messages, and manages the tenant's templates. A message is queued at most once
 * per dedupe key (M1); reminders wait for the sending window (M2); a number the provider refused for
 * good is not messaged again (stored as SUPPRESSED, so staff can see why nothing went out).
 */
@Service
public class NotificationQueue {

    private final JdbcClient jdbc;
    private final SmsSettings settings;
    private final Clock clock;

    NotificationQueue(final JdbcClient jdbc, final SmsSettings settings, final Clock clock) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.clock = clock;
    }

    /** Returns false when the dedupe key was already used. */
    @Transactional
    public boolean enqueue(
            final UUID customerId, final String recipient, final Template template, final Map<String, String> values,
            final String dedupeKey) {
        final Instant now = clock.instant();
        final String body = Template.render(bodyOf(template), values);
        final boolean flagged = jdbc.sql("select count(*) from notifications_phone_flag where phone = :phone")
                .param("phone", recipient).query(Long.class).single() > 0;
        final boolean enabled = settings.stored().map(SmsSettings.Stored::enabled).orElse(true);
        final String status = flagged || !enabled ? "SUPPRESSED" : "QUEUED";
        final Instant scheduled = settings.window().scheduleFor(now, template.immediate());
        return jdbc.sql("""
                        insert into notifications_notification
                            (id, tenant_id, customer_id, recipient, template_key, body, dedupe_key, status, scheduled_for,
                             next_attempt_at, failure_reason, created_at, updated_at)
                        values (:id, :tenantId, :customerId, :recipient, :template, :body, :dedupe, :status, :scheduled,
                                :scheduled, :reason, :now, :now)
                        on conflict (tenant_id, dedupe_key) do nothing
                        """)
                .param("id", UuidV7.next(clock))
                .param("tenantId", TenantContext.requireTenantId())
                .param("customerId", customerId)
                .param("recipient", recipient)
                .param("template", template.name())
                .param("body", body)
                .param("dedupe", dedupeKey)
                .param("status", status)
                .param("scheduled", Timestamp.from(scheduled))
                .param("reason", flagged ? "phone flagged by the SMS provider" : enabled ? null : "SMS disabled for this tenant")
                .param("now", Timestamp.from(now))
                .update() == 1;
    }

    public record TemplateView(String key, boolean immediate, String body, boolean custom, String sample,
                               String encoding, int length, int segments) {}

    @Transactional(readOnly = true)
    public List<TemplateView> templates() {
        final List<TemplateView> views = new ArrayList<>();
        for (final Template template : Template.values()) {
            final Optional<String> custom = custom(template);
            views.add(view(template, custom.orElse(template.defaultBody()), custom.isPresent()));
        }
        return views;
    }

    /** Saves a tenant's wording; rejects placeholders outside the allowed list (M3). */
    @Transactional
    public TemplateView saveTemplate(final Template template, final String body) {
        final Set<String> unknown = Template.unknownPlaceholders(body);
        if (!unknown.isEmpty()) {
            throw InvalidRequestException.of("body", "unknown placeholders " + unknown + "; allowed: "
                    + Template.PLACEHOLDERS.stream().sorted().toList());
        }
        jdbc.sql("""
                        insert into notifications_template (tenant_id, template_key, body, updated_at)
                        values (:tenantId, :key, :body, :now)
                        on conflict (tenant_id, template_key, channel) do update set body = excluded.body, updated_at = excluded.updated_at
                        """)
                .param("tenantId", TenantContext.requireTenantId())
                .param("key", template.name())
                .param("body", body.strip())
                .param("now", Timestamp.from(clock.instant()))
                .update();
        return view(template, body.strip(), true);
    }

    /** Back to the platform default. */
    @Transactional
    public TemplateView resetTemplate(final Template template) {
        jdbc.sql("delete from notifications_template where template_key = :key").param("key", template.name()).update();
        return view(template, template.defaultBody(), false);
    }

    /** What a body costs, rendered with sample values (the editor shows it on every keystroke). */
    public TemplateView preview(final Template template, final String body) {
        return view(template, body, true);
    }

    String bodyOf(final Template template) {
        return custom(template).orElse(template.defaultBody());
    }

    private Optional<String> custom(final Template template) {
        return jdbc.sql("select body from notifications_template where template_key = :key and channel = 'SMS'")
                .param("key", template.name()).query(String.class).optional();
    }

    private static TemplateView view(final Template template, final String body, final boolean custom) {
        final String sample = Template.render(body, Template.SAMPLE);
        final SmsSegments segments = SmsSegments.of(sample);
        return new TemplateView(template.name(), template.immediate(), body, custom, sample,
                segments.encoding(), segments.length(), segments.segments());
    }
}
