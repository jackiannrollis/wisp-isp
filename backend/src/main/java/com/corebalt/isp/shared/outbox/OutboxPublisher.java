package com.corebalt.isp.shared.outbox;

import com.corebalt.isp.shared.tenant.TenantContext;
import com.corebalt.isp.shared.web.CorrelationIdFilter;
import java.sql.Timestamp;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes an event to {@code outbox_event} inside the caller's transaction (E1), so the business
 * change and the event commit or roll back together. {@link OutboxRelay} publishes it afterwards.
 */
@Component
public class OutboxPublisher {

    private static final Pattern MODULE_PACKAGE =
            Pattern.compile("^com\\.corebalt\\.isp\\.([a-z]+)\\.api(\\..+)?$");

    private final JdbcClient jdbc;
    private final JsonMapper jsonMapper;

    public OutboxPublisher(final JdbcClient jdbc, final JsonMapper jsonMapper) {
        this.jdbc = jdbc;
        this.jsonMapper = jsonMapper;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(final DomainEvent event) {
        if (!event.tenantId().equals(TenantContext.requireTenantId())) {
            throw new IllegalStateException(
                    "E2/T1: event tenant " + event.tenantId() + " differs from the tenant context");
        }
        final String module = moduleOf(event);
        final String eventType = event.getClass().getSimpleName();
        jdbc.sql(
                        """
                        insert into outbox_event
                            (id, tenant_id, module, event_type, version, routing_key,
                             payload, correlation_id, occurred_at)
                        values (:id, :tenantId, :module, :eventType, :version, :routingKey,
                                cast(:payload as jsonb), :correlationId, :occurredAt)
                        """)
                .param("id", event.eventId())
                .param("tenantId", event.tenantId())
                .param("module", module)
                .param("eventType", eventType)
                .param("version", event.version())
                .param("routingKey", routingKey(module, eventType, event.version()))
                .param("payload", jsonMapper.writeValueAsString(event))
                .param("correlationId", MDC.get(CorrelationIdFilter.MDC_KEY))
                .param("occurredAt", Timestamp.from(event.occurredAt()))
                .update();
    }

    /** {@code <module>.<Event>.v<version>}, e.g. {@code payments.PaymentReceived.v1}. */
    public static String routingKey(final String module, final String eventType, final int version) {
        return module + "." + eventType + ".v" + version;
    }

    static String moduleOf(final DomainEvent event) {
        final Matcher matcher = MODULE_PACKAGE.matcher(event.getClass().getPackageName());
        if (!matcher.matches()) {
            throw new IllegalArgumentException(
                    "E3: events live in a module's api package, not " + event.getClass().getName());
        }
        return matcher.group(1);
    }
}
