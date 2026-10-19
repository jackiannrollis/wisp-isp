package com.corebalt.isp.network.infrastructure;

import com.corebalt.isp.network.api.NetworkAccessChanged;
import com.corebalt.isp.network.api.SessionReauthorizationFailed;
import com.corebalt.isp.network.api.SessionReauthorized;
import com.corebalt.isp.network.application.AccessChanges;
import com.corebalt.isp.network.application.SessionViews;
import com.corebalt.isp.shared.outbox.EventQueues;
import com.corebalt.isp.shared.outbox.IdempotentConsumer;
import com.corebalt.isp.subscriptions.api.SubscriptionPackageChanged;
import com.corebalt.isp.subscriptions.api.SubscriptionRenewed;
import com.corebalt.isp.subscriptions.api.SubscriptionStateChanged;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * The API's half of CoA and Disconnect (N5): subscription changes become NetworkAccessChanged per
 * live session, once each (E2). The tunnel worker consumes {@link #COA} (ADR 0011).
 */
@Component
class AccessChangeListeners {

    static final String STATES = "network.state-changes";
    static final String RENEWALS = "network.renewals";
    static final String PACKAGES = "network.package-changes";
    /** Declared by the API as well as the worker, so a restore waits in the queue while no worker runs. */
    static final String COA = "network.coa";
    /** The worker's outcomes, kept per line for the pay page (SessionViews). */
    static final String COA_DONE = "network.coa-done";
    static final String COA_FAILED = "network.coa-failed";

    private final IdempotentConsumer consumer;
    private final AccessChanges changes;
    private final SessionViews sessions;
    private final JsonMapper json;

    AccessChangeListeners(final IdempotentConsumer consumer, final AccessChanges changes, final SessionViews sessions,
                          final JsonMapper json) {
        this.consumer = consumer;
        this.changes = changes;
        this.sessions = sessions;
        this.json = json;
    }

    @RabbitListener(queues = COA_DONE)
    void coaDone(final Message message) {
        final SessionReauthorized event = EventQueues.read(json, message, SessionReauthorized.class);
        consumer.handleOnce(COA_DONE, message, () -> sessions.recordOutcome(event));
    }

    @RabbitListener(queues = COA_FAILED)
    void coaFailed(final Message message) {
        final SessionReauthorizationFailed event = EventQueues.read(json, message, SessionReauthorizationFailed.class);
        consumer.handleOnce(COA_FAILED, message, () -> sessions.recordOutcome(event));
    }

    @RabbitListener(queues = STATES)
    void stateChanged(final Message message) {
        final SubscriptionStateChanged event = EventQueues.read(json, message, SubscriptionStateChanged.class);
        consumer.handleOnce(STATES, message, () -> changes.stateChanged(event));
    }

    @RabbitListener(queues = RENEWALS)
    void renewed(final Message message) {
        final SubscriptionRenewed event = EventQueues.read(json, message, SubscriptionRenewed.class);
        consumer.handleOnce(RENEWALS, message, () -> changes.renewed(event));
    }

    @RabbitListener(queues = PACKAGES)
    void packageChanged(final Message message) {
        final SubscriptionPackageChanged event = EventQueues.read(json, message, SubscriptionPackageChanged.class);
        consumer.handleOnce(PACKAGES, message, () -> changes.packageChanged(event));
    }

    @Configuration(proxyBeanMethods = false)
    static class Queues {

        @Bean
        Declarables networkStateChangesQueue() {
            return EventQueues.declare(STATES, EventQueues.routingKey(SubscriptionStateChanged.class, 1));
        }

        @Bean
        Declarables networkRenewalsQueue() {
            return EventQueues.declare(RENEWALS, EventQueues.routingKey(SubscriptionRenewed.class, 1));
        }

        @Bean
        Declarables networkPackageChangesQueue() {
            return EventQueues.declare(PACKAGES, EventQueues.routingKey(SubscriptionPackageChanged.class, 1));
        }

        @Bean
        Declarables networkCoaDoneQueue() {
            return EventQueues.declare(COA_DONE, EventQueues.routingKey(SessionReauthorized.class, 1));
        }

        @Bean
        Declarables networkCoaFailedQueue() {
            return EventQueues.declare(COA_FAILED, EventQueues.routingKey(SessionReauthorizationFailed.class, 1));
        }

        @Bean
        Declarables networkCoaQueue() {
            return EventQueues.declare(COA, EventQueues.routingKey(NetworkAccessChanged.class, 1));
        }
    }
}
