package com.corebalt.isp.notifications.infrastructure;

import com.corebalt.isp.network.api.RouterOffline;
import com.corebalt.isp.network.api.RouterOnline;
import com.corebalt.isp.network.api.SessionReauthorizationFailed;
import com.corebalt.isp.network.api.SessionReauthorized;
import com.corebalt.isp.network.api.UsageThresholdReached;
import com.corebalt.isp.notifications.application.CoaAlerts;
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
    static final String COA_FAILED = "notifications.coa-failed";
    static final String COA_DONE = "notifications.coa-done";
    static final String USAGE = "notifications.usage-thresholds";

    private final IdempotentConsumer consumer;
    private final MessageComposer composer;
    private final RouterAlerts routerAlerts;
    private final CoaAlerts coaAlerts;
    private final JsonMapper json;

    NotificationListeners(final IdempotentConsumer consumer, final MessageComposer composer, final RouterAlerts routerAlerts,
                          final CoaAlerts coaAlerts, final JsonMapper json) {
        this.consumer = consumer;
        this.composer = composer;
        this.routerAlerts = routerAlerts;
        this.coaAlerts = coaAlerts;
        this.json = json;
    }

    @RabbitListener(queues = COA_FAILED)
    void coaFailed(final Message message) {
        final SessionReauthorizationFailed event = EventQueues.read(json, message, SessionReauthorizationFailed.class);
        consumer.handleOnce(COA_FAILED, message, () -> coaAlerts.failed(event));
    }

    @RabbitListener(queues = COA_DONE)
    void coaDone(final Message message) {
        final SessionReauthorized event = EventQueues.read(json, message, SessionReauthorized.class);
        consumer.handleOnce(COA_DONE, message, () -> coaAlerts.reauthorized(event));
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

    @RabbitListener(queues = USAGE)
    void usageThresholdReached(final Message message) {
        final UsageThresholdReached event = EventQueues.read(json, message, UsageThresholdReached.class);
        consumer.handleOnce(USAGE, message, () -> composer.usageThresholdReached(event));
    }

    @Configuration(proxyBeanMethods = false)
    static class Queues {

        @Bean
        Declarables notificationsUsageThresholdsQueue() {
            return EventQueues.declare(USAGE, EventQueues.routingKey(UsageThresholdReached.class, 1));
        }

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
        Declarables notificationsCoaFailedQueue() {
            return EventQueues.declare(COA_FAILED, EventQueues.routingKey(SessionReauthorizationFailed.class, 1));
        }

        @Bean
        Declarables notificationsCoaDoneQueue() {
            return EventQueues.declare(COA_DONE, EventQueues.routingKey(SessionReauthorized.class, 1));
        }

        @Bean
        Declarables notificationsPackagesQueue() {
            return EventQueues.declare(PACKAGES, EventQueues.routingKey(SubscriptionPackageChanged.class, 1));
        }
    }
}
