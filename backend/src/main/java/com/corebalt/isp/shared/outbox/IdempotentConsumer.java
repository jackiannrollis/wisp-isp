package com.corebalt.isp.shared.outbox;

import com.corebalt.isp.shared.tenant.TenantContext;
import com.corebalt.isp.shared.web.CorrelationIdFilter;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs a consumer's handler at most once per (consumer, eventId) (E2). The tenant context is set
 * from the message before any data is touched; the processed-event row and the handler's changes
 * commit in the same transaction, so a redelivered message after a crash is either fully applied
 * once or not at all.
 */
@Component
public class IdempotentConsumer {

    private static final Logger log = LoggerFactory.getLogger(IdempotentConsumer.class);

    private final JdbcClient jdbc;
    private final TransactionTemplate transactionTemplate;

    public IdempotentConsumer(final JdbcClient jdbc, final TransactionTemplate transactionTemplate) {
        this.jdbc = jdbc;
        this.transactionTemplate = transactionTemplate;
    }

    /** Returns {@code true} when the handler ran, {@code false} for a duplicate delivery. */
    public boolean handleOnce(final String consumer, final Message message, final Runnable handler) {
        final MessageProperties properties = message.getMessageProperties();
        final UUID eventId = requiredUuid(properties, EventHeaders.EVENT_ID);
        final UUID tenantId = requiredUuid(properties, EventHeaders.TENANT_ID);
        final String correlationId = properties.getHeader(EventHeaders.CORRELATION_ID);
        if (correlationId != null) {
            MDC.put(CorrelationIdFilter.MDC_KEY, correlationId);
        }
        try {
            return TenantContext.callAsTenant(
                    tenantId,
                    () ->
                            Boolean.TRUE.equals(
                                    transactionTemplate.execute(
                                            status -> {
                                                if (!markProcessed(consumer, eventId, tenantId)) {
                                                    log.debug(
                                                            "Skipping duplicate event {} for {}",
                                                            eventId,
                                                            consumer);
                                                    return false;
                                                }
                                                handler.run();
                                                return true;
                                            })));
        } finally {
            MDC.remove(CorrelationIdFilter.MDC_KEY);
        }
    }

    private boolean markProcessed(final String consumer, final UUID eventId, final UUID tenantId) {
        return jdbc.sql(
                                """
                                insert into shared_processed_event (consumer, event_id, tenant_id)
                                values (:consumer, :eventId, :tenantId)
                                on conflict do nothing
                                """)
                        .param("consumer", consumer)
                        .param("eventId", eventId)
                        .param("tenantId", tenantId)
                        .update()
                == 1;
    }

    private static UUID requiredUuid(final MessageProperties properties, final String header) {
        final Object value = properties.getHeader(header);
        if (value == null) {
            throw new AmqpRejectAndDontRequeueException("E2: message without " + header + " header");
        }
        try {
            return UUID.fromString(value.toString());
        } catch (IllegalArgumentException e) {
            throw new AmqpRejectAndDontRequeueException("E2: malformed " + header + " header", e);
        }
    }
}
