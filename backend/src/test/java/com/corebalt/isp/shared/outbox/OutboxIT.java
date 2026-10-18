package com.corebalt.isp.shared.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.corebalt.isp.shared.id.UuidV7;
import com.corebalt.isp.shared.tenant.TenantContext;
import com.corebalt.isp.support.IntegrationTest;
import com.corebalt.isp.support.TestTenants;
import com.corebalt.isp.tenancy.api.TestThingHappened;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

/** Outbox → relay → RabbitMQ → idempotent consumer, against real PostgreSQL and RabbitMQ. */
class OutboxIT extends IntegrationTest {

    private static final String ROUTING_KEY = "tenancy.TestThingHappened.v1";

    @Autowired OutboxPublisher publisher;
    @Autowired OutboxRelay relay;
    @Autowired IdempotentConsumer idempotentConsumer;
    @Autowired JdbcClient jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired RabbitTemplate rabbit;
    @Autowired AmqpAdmin amqpAdmin;
    @Autowired TopicExchange eventsExchange;

    UUID tenant;
    Queue queue;

    @BeforeEach
    void setUp() {
        tenant = TestTenants.create(jdbc, tx);
        queue = QueueBuilder.durable("test.outbox." + UUID.randomUUID()).quorum().build();
        amqpAdmin.declareQueue(queue);
        final Binding binding = BindingBuilder.bind(queue).to(eventsExchange).with(ROUTING_KEY);
        amqpAdmin.declareBinding(binding);
    }

    @Test
    void E1_eventIsWrittenInTheCallersTransactionAndRelayedWithHeaders() {
        final TestThingHappened event = event("committed");
        TenantContext.runAsTenant(tenant, () -> tx.executeWithoutResult(s -> publisher.publish(event)));

        final int published = TenantContext.callAsTenant(tenant, relay::relayTenant);
        assertThat(published).isEqualTo(1);

        final Message message = rabbit.receive(queue.getName(), 10_000);
        assertThat(message).isNotNull();
        assertThat(message.getMessageProperties().getReceivedRoutingKey()).isEqualTo(ROUTING_KEY);
        assertThat(message.getMessageProperties().getMessageId()).isEqualTo(event.eventId().toString());
        assertThat((String) message.getMessageProperties().getHeader(EventHeaders.EVENT_ID))
                .isEqualTo(event.eventId().toString());
        assertThat((String) message.getMessageProperties().getHeader(EventHeaders.TENANT_ID))
                .isEqualTo(tenant.toString());
        assertThat(new String(message.getBody(), StandardCharsets.UTF_8))
                .contains("\"note\": \"committed\"")
                .contains(event.eventId().toString());

        final java.sql.Timestamp publishedAt =
                TenantContext.callAsTenant(
                        tenant,
                        () ->
                                tx.execute(
                                        s ->
                                                jdbc.sql("select published_at from outbox_event where id = :id")
                                                        .param("id", event.eventId())
                                                        .query(java.sql.Timestamp.class)
                                                        .single()));
        assertThat(publishedAt).isNotNull();

        assertThat(TenantContext.callAsTenant(tenant, relay::relayTenant))
                .as("a published row is not sent again")
                .isZero();
    }

    @Test
    void E1_rolledBackBusinessChangeLeavesNoEvent() {
        final TestThingHappened event = event("rolled back");
        TenantContext.runAsTenant(
                tenant,
                () ->
                        tx.executeWithoutResult(
                                s -> {
                                    publisher.publish(event);
                                    s.setRollbackOnly();
                                }));

        assertThat(TenantContext.callAsTenant(tenant, relay::relayTenant)).isZero();
        assertThat(rabbit.receive(queue.getName(), 500)).isNull();
    }

    @Test
    void E1_publishingOutsideATransactionIsRefused() {
        assertThatThrownBy(
                        () -> TenantContext.runAsTenant(tenant, () -> publisher.publish(event("no tx"))))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void E2_eventForAnotherTenantIsRefused() {
        final UUID otherTenant = TestTenants.create(jdbc, tx);
        assertThatThrownBy(
                        () ->
                                TenantContext.runAsTenant(
                                        tenant,
                                        () ->
                                                tx.executeWithoutResult(
                                                        s ->
                                                                publisher.publish(
                                                                        new TestThingHappened(
                                                                                UuidV7.next(),
                                                                                otherTenant,
                                                                                Instant.now(),
                                                                                "wrong")))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("E2");
    }

    @Test
    void E2_consumerHandlesARedeliveredEventOnceWithTheEventsTenantContext() {
        final TestThingHappened event = event("redelivered");
        TenantContext.runAsTenant(tenant, () -> tx.executeWithoutResult(s -> publisher.publish(event)));
        TenantContext.callAsTenant(tenant, relay::relayTenant);
        final Message message = rabbit.receive(queue.getName(), 10_000);
        assertThat(message).isNotNull();

        final AtomicInteger handled = new AtomicInteger();
        final Runnable handler =
                () -> {
                    assertThat(TenantContext.requireTenantId()).isEqualTo(tenant);
                    handled.incrementAndGet();
                };

        assertThat(idempotentConsumer.handleOnce("tenancy.test-consumer", message, handler)).isTrue();
        assertThat(idempotentConsumer.handleOnce("tenancy.test-consumer", message, handler)).isFalse();
        assertThat(idempotentConsumer.handleOnce("billing.other-consumer", message, handler))
                .as("each consumer tracks its own processed events")
                .isTrue();
        assertThat(handled).hasValue(2);
        assertThat(TenantContext.current()).isEmpty();
    }

    @Test
    void E2_failedHandlerDoesNotMarkTheEventProcessed() {
        final TestThingHappened event = event("fails once");
        TenantContext.runAsTenant(tenant, () -> tx.executeWithoutResult(s -> publisher.publish(event)));
        TenantContext.callAsTenant(tenant, relay::relayTenant);
        final Message message = rabbit.receive(queue.getName(), 10_000);
        assertThat(message).isNotNull();

        assertThatThrownBy(
                        () ->
                                idempotentConsumer.handleOnce(
                                        "tenancy.flaky-consumer",
                                        message,
                                        () -> {
                                            throw new IllegalStateException("transient");
                                        }))
                .hasMessage("transient");
        final AtomicInteger handled = new AtomicInteger();
        assertThat(idempotentConsumer.handleOnce("tenancy.flaky-consumer", message, handled::incrementAndGet))
                .isTrue();
        assertThat(handled).hasValue(1);
    }

    @Test
    void relayAllVisitsEveryActiveTenant() {
        final UUID secondTenant = TestTenants.create(jdbc, tx);
        final TestThingHappened first = event("first tenant");
        final TestThingHappened second =
                new TestThingHappened(UuidV7.next(), secondTenant, now(), "second tenant");
        TenantContext.runAsTenant(tenant, () -> tx.executeWithoutResult(s -> publisher.publish(first)));
        TenantContext.runAsTenant(
                secondTenant, () -> tx.executeWithoutResult(s -> publisher.publish(second)));

        relay.relayAll();

        final Message a = rabbit.receive(queue.getName(), 10_000);
        final Message b = rabbit.receive(queue.getName(), 10_000);
        assertThat(a).isNotNull();
        assertThat(b).isNotNull();
        assertThat(java.util.List.of(a.getMessageProperties().getMessageId(), b.getMessageProperties().getMessageId()))
                .containsExactlyInAnyOrder(first.eventId().toString(), second.eventId().toString());
    }

    private TestThingHappened event(final String note) {
        return new TestThingHappened(UuidV7.next(), tenant, now(), note);
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
