package com.corebalt.isp.shared.outbox;

import com.corebalt.isp.shared.tenant.TenantIterator;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Publishes committed outbox rows to RabbitMQ (E1), one tenant at a time through {@link
 * TenantIterator}. Rows are locked with {@code skip locked} so several API instances can relay
 * concurrently; a row is marked published only after the broker confirms it. If the broker is
 * down, rows wait and {@code attempts}/{@code last_error} record why (E4: nothing waits on this).
 */
@Component
@ConditionalOnProperty(name = "isp.outbox.relay.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final long CONFIRM_TIMEOUT_MS = 5_000;

    private final TenantIterator tenantIterator;
    private final JdbcClient jdbc;
    private final RabbitTemplate rabbitTemplate;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;
    private final int batchSize;

    public OutboxRelay(
            final TenantIterator tenantIterator,
            final JdbcClient jdbc,
            final RabbitTemplate rabbitTemplate,
            final TransactionTemplate transactionTemplate,
            final Clock clock,
            @Value("${isp.outbox.relay.batch-size:100}") final int batchSize) {
        this.tenantIterator = tenantIterator;
        this.jdbc = jdbc;
        this.rabbitTemplate = rabbitTemplate;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${isp.outbox.relay.interval:1s}")
    public void relayAll() {
        tenantIterator.forEachTenant("outbox-relay", tenantId -> relayTenant());
    }

    /** Publishes one batch for the tenant in the current context; returns the number published. */
    public int relayTenant() {
        final Integer published = transactionTemplate.execute(status -> publishBatch());
        return published == null ? 0 : published;
    }

    private int publishBatch() {
        final List<OutboxRow> rows =
                jdbc.sql(
                                """
                                select id, tenant_id, routing_key, payload::text as payload,
                                       correlation_id, occurred_at
                                from outbox_event
                                where published_at is null
                                order by id
                                limit :limit
                                for update skip locked
                                """)
                        .param("limit", batchSize)
                        .query(
                                (rs, rowNum) ->
                                        new OutboxRow(
                                                rs.getObject("id", UUID.class),
                                                rs.getObject("tenant_id", UUID.class),
                                                rs.getString("routing_key"),
                                                rs.getString("payload"),
                                                rs.getString("correlation_id"),
                                                rs.getTimestamp("occurred_at").toInstant()))
                        .list();
        int published = 0;
        for (final OutboxRow row : rows) {
            try {
                send(row);
                jdbc.sql("update outbox_event set published_at = :now where id = :id")
                        .param("now", Timestamp.from(Instant.now(clock)))
                        .param("id", row.id())
                        .update();
                published++;
            } catch (RuntimeException e) {
                log.warn("Outbox publish failed for event {} ({})", row.id(), row.routingKey(), e);
                jdbc.sql(
                                """
                                update outbox_event
                                set attempts = attempts + 1, last_error = left(:error, 1000)
                                where id = :id
                                """)
                        .param("error", e.getClass().getSimpleName() + ": " + e.getMessage())
                        .param("id", row.id())
                        .update();
                // Keep per-tenant order: stop at the first failure and retry on the next tick.
                break;
            }
        }
        return published;
    }

    private void send(final OutboxRow row) {
        final Message message =
                MessageBuilder.withBody(row.payload().getBytes(StandardCharsets.UTF_8))
                        .setContentType("application/json")
                        .setContentEncoding(StandardCharsets.UTF_8.name())
                        .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                        .setMessageId(row.id().toString())
                        .setHeader(EventHeaders.EVENT_ID, row.id().toString())
                        .setHeader(EventHeaders.TENANT_ID, row.tenantId().toString())
                        .setHeader(EventHeaders.CORRELATION_ID, row.correlationId())
                        .setHeader(EventHeaders.OCCURRED_AT, row.occurredAt().toString())
                        .build();
        rabbitTemplate.invoke(
                operations -> {
                    operations.send(AmqpConfig.EVENTS_EXCHANGE, row.routingKey(), message);
                    operations.waitForConfirmsOrDie(CONFIRM_TIMEOUT_MS);
                    return null;
                });
    }

    private record OutboxRow(
            UUID id,
            UUID tenantId,
            String routingKey,
            String payload,
            String correlationId,
            Instant occurredAt) {}
}
