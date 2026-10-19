package com.corebalt.isp.notifications.infrastructure;

import com.corebalt.isp.network.api.RouterOffline;
import com.corebalt.isp.network.api.RouterOnline;
import com.corebalt.isp.notifications.application.MessageComposer;
import com.corebalt.isp.notifications.application.RouterAlerts;
import com.corebalt.isp.payments.api.PaymentUnmatched;
import com.corebalt.isp.shared.outbox.EventQueues;
import com.corebalt.isp.shared.outbox.IdempotentConsumer;
import com.corebalt.isp.subscriptions.api.CreditApplied;
import com.corebalt.isp.subscriptions.api.SubscriptionPackageChanged;
import com.corebalt.isp.subscriptions.api.SubscriptionReminderDue;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** The events customers and staff hear about (docs/02: notifications consumes most events), once each (E2). */
@Component
class NotificationListeners {

    static final String REMINDERS = "notifications.reminders";
    static final String CREDITS = "notifications.credits";
    static final String UNMATCHED = "notifications.unmatched-payments";
    static final String PACKAGES = "notifications.package-changes";
    static final String ROUTERS_OFFLINE = "notifications.routers-offline";
    static final String ROUTERS_ONLINE = "notifications.routers-online";

    private final IdempotentConsumer consumer;
    private final MessageComposer composer;
    private final RouterAlerts routerAlerts;
    private final JsonMapper json;

    NotificationListeners(final IdempotentConsumer consumer, final MessageComposer composer, final RouterAlerts routerAlerts,
                          final JsonMapper json) {
        this.consumer = consumer;
        this.composer = composer;
        this.routerAlerts = routerAlerts;
        this.json = json;
    }

    @RabbitListener(queues = ROUTERS_OFFLINE)
    void routerOffline(final Message message) {
        final RouterOffline event = EventQueues.read(json, message, RouterOffline.class);
        consumer.handleOnce(ROUTERS_OFFLINE, message, () -> routerAlerts.offline(event));
    }

    @RabbitListener(queues = ROUTERS_ONLINE)
    void routerOnline(final Message message) {
        final RouterOnline event = EventQueues.read(json, message, RouterOnline.class);
        consumer.handleOnce(ROUTERS_ONLINE, message, () -> routerAlerts.online(event));
    }

    @RabbitListener(queues = REMINDERS)
    void reminder(final Message message) {
        final SubscriptionReminderDue event = EventQueues.read(json, message, SubscriptionReminderDue.class);
        consumer.handleOnce(REMINDERS, message, () -> composer.reminder(event));
    }

    @RabbitListener(queues = CREDITS)
    void credit(final Message message) {
        final CreditApplied event = EventQueues.read(json, message, CreditApplied.class);
        consumer.handleOnce(CREDITS, message, () -> composer.creditApplied(event));
    }

    @RabbitListener(queues = UNMATCHED)
    void unmatched(final Message message) {
        final PaymentUnmatched event = EventQueues.read(json, message, PaymentUnmatched.class);
        consumer.handleOnce(UNMATCHED, message, () -> composer.unmatched(event));
    }

    @RabbitListener(queues = PACKAGES)
    void packageChanged(final Message message) {
        final SubscriptionPackageChanged event = EventQueues.read(json, message, SubscriptionPackageChanged.class);
        consumer.handleOnce(PACKAGES, message, () -> composer.packageChanged(event));
    }

    @Configuration(proxyBeanMethods = false)
    static class Queues {

        @Bean
        Declarables notificationsRemindersQueue() {
            return EventQueues.declare(REMINDERS, EventQueues.routingKey(SubscriptionReminderDue.class, 1));
        }

        @Bean
        Declarables notificationsCreditsQueue() {
            return EventQueues.declare(CREDITS, EventQueues.routingKey(CreditApplied.class, 1));
        }

        @Bean
        Declarables notificationsUnmatchedQueue() {
            return EventQueues.declare(UNMATCHED, EventQueues.routingKey(PaymentUnmatched.class, 1));
        }

        @Bean
        Declarables notificationsRoutersOfflineQueue() {
            return EventQueues.declare(ROUTERS_OFFLINE, EventQueues.routingKey(RouterOffline.class, 1));
        }

        @Bean
        Declarables notificationsRoutersOnlineQueue() {
            return EventQueues.declare(ROUTERS_ONLINE, EventQueues.routingKey(RouterOnline.class, 1));
        }

        @Bean
        Declarables notificationsPackagesQueue() {
            return EventQueues.declare(PACKAGES, EventQueues.routingKey(SubscriptionPackageChanged.class, 1));
        }
    }
}
