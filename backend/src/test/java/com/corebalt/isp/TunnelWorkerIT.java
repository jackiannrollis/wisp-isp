package com.corebalt.isp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.corebalt.isp.shared.outbox.OutboxRelay;
import com.corebalt.isp.shared.tenant.TenantContext;
import com.corebalt.isp.support.Api;
import com.corebalt.isp.support.FakeRouterOs;
import com.corebalt.isp.support.IntegrationTest;
import com.corebalt.isp.support.OwnerSql;
import com.corebalt.isp.support.TestTenants;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.config.TaskManagementConfigUtils;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The tunnel worker exactly as compose starts it (ADR 0011): the application in profile
 * {@code worker}, connected as isp_worker, beside the API of this test. Its scheduled poll and its
 * RouterPollRequested consumer must work with isp_worker's grants alone, and its HTTP port must
 * answer nothing but health.
 */
class TunnelWorkerIT extends IntegrationTest {

    @Value("${local.server.port}")
    int port;

    @Autowired JdbcClient jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired OutboxRelay relay;
    @Autowired Environment environment;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void ADR0011_theWorkerPollsConsumesPollRequestsAndServesOnlyHealth() throws Exception {
        final UUID tenant = TestTenants.create(jdbc, tx);
        final Api api = new Api(port, tenant);
        final String first = monitoredRouter(api, "127.0.6.2", "Worker-A");

        try (ConfigurableApplicationContext worker = startWorker()) {
            final int workerPort = Integer.parseInt(worker.getEnvironment().getProperty("local.server.port"));
            assertThat(get(workerPort, "/actuator/health/readiness").statusCode()).isEqualTo(200);
            assertThat(get(workerPort, "/actuator/prometheus").statusCode()).as("scraped by Prometheus").isEqualTo(200);
            assertThat(get(workerPort, "/api/v1/customers").statusCode()).isEqualTo(404);
            assertThat(get(workerPort, "/api/v1/auth/login").statusCode()).isEqualTo(404);
            assertThat(get(workerPort, "/.well-known/jwks.json").statusCode()).isEqualTo(404);
            // isp.scheduling.enabled=false must mean no @Scheduled processing at all: the API's jobs (SMS
            // sender, renewals, M-Pesa sweeps) belong to api-1 alone and would fail here as isp_worker.
            assertThat(worker.containsBean(TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME))
                    .as("no @Scheduled processor in the worker").isFalse();

            // The scheduled round, as isp_worker.
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(status(first)).isEqualTo("ONLINE"));
            assertThat(OwnerSql.client().sql("select ppp_active_count from network_router where id = :id")
                    .param("id", UUID.fromString(first)).query(Integer.class).single())
                    .as("the /ppp/active snapshot is written with isp_worker's grants").isZero();

            // A poll request published by the API (through its relay) and consumed only by the worker.
            final String second = monitoredRouter(api, "127.0.6.3", "Worker-B");
            assertThat(api.post("/api/v1/routers/" + second + "/poll").status()).isEqualTo(202);
            await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(300)).untilAsserted(() -> {
                TenantContext.callAsTenant(tenant, relay::relayTenant);
                assertThat(status(second)).isEqualTo("ONLINE");
            });
            assertThat(OwnerSql.client().sql("select count(*) from shared_processed_event where consumer = 'network.router-poll' and tenant_id = :t")
                    .param("t", tenant).query(Long.class).single()).isOne();
        }
    }

    private String monitoredRouter(final Api api, final String tunnelIp, final String name) {
        final Api.Response created = api.post("/api/v1/routers", "{\"name\":\"" + name + "\",\"tunnelIp\":\"" + tunnelIp + "\"}");
        assertThat(created.status()).as(created.body()).isEqualTo(201);
        final String id = created.json().get("router").get("id").asString();
        assertThat(api.put("/api/v1/routers/" + id + "/rest-credential",
                "{\"username\":\"" + FakeRouterOs.USERNAME + "\",\"password\":\"" + FakeRouterOs.PASSWORD + "\"}").status())
                .isEqualTo(200);
        return id;
    }

    private static String status(final String routerId) {
        return OwnerSql.client().sql("select status from network_router where id = :id")
                .param("id", UUID.fromString(routerId)).query(String.class).single();
    }

    private ConfigurableApplicationContext startWorker() {
        return new SpringApplication(IspApplication.class).run(
                "--spring.profiles.active=test,worker",
                "--server.port=0",
                "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=isp_worker",
                "--spring.datasource.password=isp_worker_test_only",
                "--spring.rabbitmq.host=" + environment.getProperty("spring.rabbitmq.host"),
                "--spring.rabbitmq.port=" + environment.getProperty("spring.rabbitmq.port"),
                "--spring.rabbitmq.username=" + environment.getProperty("spring.rabbitmq.username"),
                "--spring.rabbitmq.password=" + environment.getProperty("spring.rabbitmq.password"),
                "--spring.data.redis.host=" + environment.getProperty("spring.data.redis.host"),
                "--spring.data.redis.port=" + environment.getProperty("spring.data.redis.port"),
                "--isp.secrets.master-key=" + environment.getProperty("isp.secrets.master-key"),
                "--isp.worker.routeros.port=" + ROUTER_OS.port(),
                "--isp.worker.poll-initial-delay=1s",
                "--isp.worker.poll-interval=1h");
    }

    private HttpResponse<String> get(final int workerPort, final String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + workerPort + path)).build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
