package com.corebalt.isp.reporting.infrastructure;

import com.corebalt.isp.network.api.LineDisconnectedByStaff;
import com.corebalt.isp.network.api.SessionReauthorized;
import com.corebalt.isp.reporting.application.Gate1Service;
import com.corebalt.isp.reporting.application.ManualReconnects;
import com.corebalt.isp.shared.outbox.EventQueues;
import com.corebalt.isp.shared.outbox.IdempotentConsumer;
import com.corebalt.isp.subscriptions.api.CreditApplied;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** The facts Gate 1 is measured from (ADR 0018), once each (E2). */
@Component
class ReportingListeners {

    static final String CREDITS = "reporting.credits";
    static final String REAUTHORIZED = "reporting.reauthorizations";
    static final String STAFF_DISCONNECTS = "reporting.staff-disconnects";

    private final IdempotentConsumer consumer;
    private final Gate1Service gate1;
    private final ManualReconnects manualReconnects;
    private final JsonMapper json;

    ReportingListeners(final IdempotentConsumer consumer, final Gate1Service gate1, final ManualReconnects manualReconnects,
                       final JsonMapper json) {
        this.consumer = consumer;
        this.gate1 = gate1;
        this.manualReconnects = manualReconnects;
        this.json = json;
    }

    @RabbitListener(queues = CREDITS)
    void credit(final Message message) {
        final CreditApplied event = EventQueues.read(json, message, CreditApplied.class);
        consumer.handleOnce(CREDITS, message, () -> gate1.recordCredit(event));
    }

    @RabbitListener(queues = REAUTHORIZED)
    void reauthorized(final Message message) {
        final SessionReauthorized event = EventQueues.read(json, message, SessionReauthorized.class);
        consumer.handleOnce(REAUTHORIZED, message, () -> gate1.recordReauthorization(event));
    }

    @RabbitListener(queues = STAFF_DISCONNECTS)
    void staffDisconnected(final Message message) {
        final LineDisconnectedByStaff event = EventQueues.read(json, message, LineDisconnectedByStaff.class);
        consumer.handleOnce(STAFF_DISCONNECTS, message, () -> manualReconnects.recordStaffDisconnect(event));
    }

    @Configuration(proxyBeanMethods = false)
    static class Queues {

        @Bean
        Declarables reportingCreditsQueue() {
            return EventQueues.declare(CREDITS, EventQueues.routingKey(CreditApplied.class, 1));
        }

        @Bean
        Declarables reportingReauthorizationsQueue() {
            return EventQueues.declare(REAUTHORIZED, EventQueues.routingKey(SessionReauthorized.class, 1));
        }

        @Bean
        Declarables reportingStaffDisconnectsQueue() {
            return EventQueues.declare(STAFF_DISCONNECTS, EventQueues.routingKey(LineDisconnectedByStaff.class, 1));
        }
    }
}
