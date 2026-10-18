package com.corebalt.isp.shared.outbox;

import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The single topic exchange for business events. Consumer queues ({@code <module>.<consumer>} with
 * a {@code .dlq}) are declared by the modules that consume, as quorum queues.
 */
@Configuration(proxyBeanMethods = false)
class AmqpConfig {

    public static final String EVENTS_EXCHANGE = "isp.events";

    @Bean
    TopicExchange eventsExchange() {
        return new TopicExchange(EVENTS_EXCHANGE, true, false);
    }
}
