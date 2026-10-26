package com.corebalt.isp.network.infrastructure;

import com.corebalt.isp.network.api.NetworkAccessChanged;
import com.corebalt.isp.network.api.RouterPollRequested;
import com.corebalt.isp.network.application.RouterPoller;
import com.corebalt.isp.network.application.SessionReauthorizer;
import com.corebalt.isp.shared.outbox.DomainEvent;
import com.corebalt.isp.shared.outbox.EventQueues;
import com.corebalt.isp.shared.outbox.IdempotentConsumer;
import com.corebalt.isp.shared.outbox.OutboxPublisher;
import com.corebalt.isp.shared.tenant.TenantContext;
import com.corebalt.isp.shared.tenant.TenantIterator;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

/**
 * The tunnel worker (ADR 0011): the same image as the API, started with {@code isp.worker.enabled}
 * (profile {@code worker}). It polls routers every 60 s on its own executor (the API's scheduled
 * jobs are off), is the only consumer of {@code network.router-poll} and {@code network.coa}
 * (CoA and Disconnect, N5), and answers HTTP only on
 * {@code /actuator/health}. It connects as isp_worker and is the only process that decrypts router
 * REST credentials, and the only one besides FreeRADIUS that reads router RADIUS secrets (ADR 0009). Nothing here exists in the API.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "isp.worker.enabled", havingValue = "true")
class TunnelWorker {

    static final String POLL_QUEUE = "network.router-poll";

    @Bean
    Declarables networkRouterPollQueue() {
        return EventQueues.declare(POLL_QUEUE, EventQueues.routingKey(RouterPollRequested.class, 1));
    }

    /** Every request but health and metrics (Prometheus, internal network) is a 404: the worker is not an API. */
    @Bean
    FilterRegistrationBean<OncePerRequestFilter> workerHttpGuard() {
        final FilterRegistrationBean<OncePerRequestFilter> registration = new FilterRegistrationBean<>(new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(final HttpServletRequest request, final HttpServletResponse response,
                                            final FilterChain chain) throws ServletException, IOException {
                final String uri = request.getRequestURI();
                if (uri.startsWith("/actuator/health") || uri.equals("/actuator/prometheus")) {
                    chain.doFilter(request, response);
                } else {
                    response.sendError(HttpServletResponse.SC_NOT_FOUND);
                }
            }
        });
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    /** The 60-second poll, tenant by tenant. */
    @Component
    @ConditionalOnProperty(name = "isp.worker.enabled", havingValue = "true")
    static class PollSchedule implements SmartLifecycle {

        private static final Logger log = LoggerFactory.getLogger(PollSchedule.class);

        private final RouterPoller poller;
        private final TenantIterator tenants;
        private final Duration interval;
        private final Duration initialDelay;
        private final String hub;
        private ScheduledExecutorService executor;

        PollSchedule(final RouterPoller poller, final TenantIterator tenants,
                     @Value("${isp.worker.poll-interval:60s}") final Duration interval,
                     @Value("${isp.worker.poll-initial-delay:10s}") final Duration initialDelay,
                     @Value("${isp.worker.hub:A}") final String hub) {
            this.poller = poller;
            this.tenants = tenants;
            this.interval = interval;
            this.initialDelay = initialDelay;
            this.hub = hub;
        }

        @Override
        public void start() {
            executor = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("router-poll").factory());
            executor.scheduleWithFixedDelay(this::pollAll, initialDelay.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
            log.info("Tunnel worker for hub {} polling routers every {}", hub, interval);
        }

        void pollAll() {
            try {
                tenants.forEachTenant("router-poll", tenantId -> poller.pollCurrentTenant());
            } catch (RuntimeException e) {
                log.error("Router poll round failed: {}", e.getClass().getSimpleName());
            }
        }

        @Override
        public void stop() {
            if (executor != null) {
                executor.shutdownNow();
            }
        }

        @Override
        public boolean isRunning() {
            return executor != null && !executor.isShutdown();
        }
    }

    /**
     * NetworkAccessChanged (N5): the CoA or Disconnect is sent outside any transaction, then its outcome is
     * published once (E2). A redelivery may send again, which is harmless: the request names the session
     * by its Acct-Session-Id. Four consumers, so one unreachable router's retries do not hold up others.
     */
    @Component
    @ConditionalOnProperty(name = "isp.worker.enabled", havingValue = "true")
    static class CoaRequests {

        private final SessionReauthorizer reauthorizer;
        private final IdempotentConsumer consumer;
        private final OutboxPublisher outbox;
        private final JsonMapper json;

        CoaRequests(final SessionReauthorizer reauthorizer, final IdempotentConsumer consumer, final OutboxPublisher outbox,
                    final JsonMapper json) {
            this.reauthorizer = reauthorizer;
            this.consumer = consumer;
            this.outbox = outbox;
            this.json = json;
        }

        @RabbitListener(queues = AccessChangeListeners.COA, autoStartup = "true", concurrency = "4")
        void accessChanged(final Message message) {
            final NetworkAccessChanged event = EventQueues.read(json, message, NetworkAccessChanged.class);
            final DomainEvent outcome = TenantContext.callAsTenant(event.tenantId(), () -> reauthorizer.send(event));
            consumer.handleOnce(AccessChangeListeners.COA, message, () -> outbox.publish(outcome));
        }
    }

    /** RouterPollRequested: started explicitly, as the worker turns every other listener off. */
    @Component
    @ConditionalOnProperty(name = "isp.worker.enabled", havingValue = "true")
    static class PollRequests {

        private final RouterPoller poller;
        private final IdempotentConsumer consumer;
        private final JsonMapper json;

        PollRequests(final RouterPoller poller, final IdempotentConsumer consumer, final JsonMapper json) {
            this.poller = poller;
            this.consumer = consumer;
            this.json = json;
        }

        @RabbitListener(queues = POLL_QUEUE, autoStartup = "true")
        void requested(final Message message) {
            final RouterPollRequested event = EventQueues.read(json, message, RouterPollRequested.class);
            consumer.handleOnce(POLL_QUEUE, message, () -> poller.pollOne(event.routerId()));
        }
    }
}
