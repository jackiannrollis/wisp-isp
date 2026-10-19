package com.corebalt.isp.subscriptions.infrastructure;

import com.corebalt.isp.billing.api.WalletCredited;
import com.corebalt.isp.shared.outbox.EventQueues;
import com.corebalt.isp.shared.outbox.IdempotentConsumer;
import com.corebalt.isp.subscriptions.application.RenewalTriggers;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code subscriptions.wallet-credited}: renews the customer's lines when money reaches the wallet,
 * once per event (E2). The balance comes from the wallet under its lock, never from the event.
 */
@Component
class WalletCreditedListener {

    static final String QUEUE = "subscriptions.wallet-credited";

    private final IdempotentConsumer consumer;
    private final RenewalTriggers renewals;
    private final JsonMapper jsonMapper;

    WalletCreditedListener(final IdempotentConsumer consumer, final RenewalTriggers renewals, final JsonMapper jsonMapper) {
        this.consumer = consumer;
        this.renewals = renewals;
        this.jsonMapper = jsonMapper;
    }

    @RabbitListener(queues = QUEUE)
    void onMessage(final Message message) {
        final WalletCredited event = EventQueues.read(jsonMapper, message, WalletCredited.class);
        consumer.handleOnce(QUEUE, message, () -> renewals.renewOnCredit(new RenewalTriggers.Credit(
                event.customerId(), event.entryType(), event.referenceId(), event.amountMinor(), event.currency())));
    }

    @Configuration(proxyBeanMethods = false)
    static class Queues {

        @Bean
        Declarables subscriptionsWalletCreditedQueue() {
            return EventQueues.declare(QUEUE, EventQueues.routingKey(WalletCredited.class, 1));
        }
    }
}
