package com.corebalt.isp.shared.outbox;

import java.util.ArrayList;
import java.util.List;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import tools.jackson.databind.json.JsonMapper;

/**
 * Consumer queues (conventions.md, Events and queues): a durable quorum queue
 * {@code <module>.<consumer>} bound to {@code isp.events} by routing key, dead-lettering into
 * {@code <module>.<consumer>.dlq}. Retries with backoff (5 s, 30 s, 2 min) are the listener
 * container's (application.yml); a message that still fails is rejected into the DLQ, which alerts.
 */
public final class EventQueues {

    private EventQueues() {}

    /** Declare in a module's configuration: {@code @Bean Declarables x() { return EventQueues.declare(...); }}. */
    public static Declarables declare(final String queue, final String... routingKeys) {
        final TopicExchange exchange = new TopicExchange(AmqpConfig.EVENTS_EXCHANGE, true, false);
        final Queue deadLetters = QueueBuilder.durable(queue + ".dlq").quorum().build();
        final Queue main =
                QueueBuilder.durable(queue)
                        .quorum()
                        .deadLetterExchange("")
                        .deadLetterRoutingKey(queue + ".dlq")
                        .build();
        final List<Declarable> declarables = new ArrayList<>(List.of(deadLetters, main));
        for (final String routingKey : routingKeys) {
            final Binding binding = BindingBuilder.bind(main).to(exchange).with(routingKey);
            declarables.add(binding);
        }
        return new Declarables(declarables);
    }

    /** The routing key of an event type: {@code <module>.<Event>.v<version>}. */
    public static String routingKey(final Class<? extends DomainEvent> type, final int version) {
        final String pkg = type.getPackageName();
        final String module = pkg.substring("com.corebalt.isp.".length(), pkg.indexOf(".api"));
        return OutboxPublisher.routingKey(module, type.getSimpleName(), version);
    }

    /** The event record in a relayed message (the outbox payload is the record as JSON). */
    public static <T extends DomainEvent> T read(final JsonMapper jsonMapper, final Message message, final Class<T> type) {
        return jsonMapper.readValue(message.getBody(), type);
    }
}
