package com.corebalt.isp.billing.infrastructure;

import com.corebalt.isp.billing.application.WalletService;
import com.corebalt.isp.payments.api.PaymentReceived;
import com.corebalt.isp.shared.outbox.EventQueues;
import com.corebalt.isp.shared.outbox.IdempotentConsumer;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code billing.payment-received}: credits the wallet for each {@link PaymentReceived}, once per
 * event (E2) and once per payment (ledger unique reference).
 */
@Component
class PaymentReceivedListener {

    static final String QUEUE = "billing.payment-received";

    private final IdempotentConsumer consumer;
    private final WalletService wallets;
    private final JsonMapper jsonMapper;

    PaymentReceivedListener(final IdempotentConsumer consumer, final WalletService wallets, final JsonMapper jsonMapper) {
        this.consumer = consumer;
        this.wallets = wallets;
        this.jsonMapper = jsonMapper;
    }

    @RabbitListener(queues = QUEUE)
    void onMessage(final Message message) {
        final PaymentReceived event = EventQueues.read(jsonMapper, message, PaymentReceived.class);
        consumer.handleOnce(QUEUE, message, () -> wallets.creditPayment(event));
    }

    @Configuration(proxyBeanMethods = false)
    static class Queues {

        @Bean
        Declarables billingPaymentReceivedQueue() {
            return EventQueues.declare(QUEUE, EventQueues.routingKey(PaymentReceived.class, 1));
        }
    }
}
