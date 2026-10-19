package com.corebalt.isp.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.utility.MountableFile;

/**
 * Base for Testcontainers integration tests: PostgreSQL 17, RabbitMQ 4 and Redis, shared by all tests in
 * the JVM. Flyway migrates as the container superuser (the owner); the application connects as
 * {@code isp_app}, which owns nothing, so row-level security is really exercised.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"isp.outbox.relay.interval=1h", "isp.subscriptions.jobs.enabled=false", "isp.billing.checks.initial-delay=1h",
            // Obvious fake master key for tests only (base64 of 32 bytes "test-master-key-not-a-secret!!!!").
            "isp.secrets.master-key=dGVzdC1tYXN0ZXIta2V5LW5vdC1hLXNlY3JldCEhISE=",
            "isp.mpesa.callback-allowed-cidrs=127.0.0.1/32,::1/128",
            "isp.mpesa.jobs.initial-delay=1h",
            "isp.mpesa.callbacks.sweep-initial-delay=1h",
            "isp.sms.initial-delay=1h",
            "isp.network.jobs.enabled=false"})
@ActiveProfiles("test")
public abstract class IntegrationTest {

    /** Throwaway password for the test container only; not a real secret. */
    static final String APP_PASSWORD = "isp_app_test_only";

    protected static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:17.11")
                    .withDatabaseName("isp")
                    .withCopyFileToContainer(
                            MountableFile.forClasspathResource("db/test-roles.sql"),
                            "/docker-entrypoint-initdb.d/00-test-roles.sql");

    /** One fake Daraja for the whole test JVM (production-shaped answers; see FakeDaraja). */
    protected static final FakeDaraja DARAJA = new FakeDaraja();

    /** One fake Africa's Talking for the whole test JVM. */
    protected static final FakeAfricasTalking AT = new FakeAfricasTalking();

    /** One fake RouterOS REST API for the whole test JVM; routers are 127.x tunnel addresses. */
    protected static final FakeRouterOs ROUTER_OS = new FakeRouterOs();

    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4-management");

    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.8").withExposedPorts(6379);

    static {
        POSTGRES.start();
        RABBIT.start();
        REDIS.start();
    }

    @DynamicPropertySource
    static void containerProperties(final DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "isp_app");
        registry.add("spring.datasource.password", () -> APP_PASSWORD);
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
        registry.add("isp.mpesa.daraja.sandbox-url", DARAJA::baseUrl);
        registry.add("isp.mpesa.daraja.production-url", DARAJA::baseUrl);
        registry.add("isp.sms.at.sandbox-url", AT::baseUrl);
        registry.add("isp.sms.at.production-url", AT::baseUrl);
        registry.add("spring.rabbitmq.host", RABBIT::getHost);
        registry.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
        registry.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
        registry.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("isp.worker.routeros.port", ROUTER_OS::port);
        registry.add("isp.worker.routeros.timeout", () -> "2s");
        registry.add("isp.auth.jwt.kid", () -> TestTokens.KID);
        registry.add("isp.auth.jwt.private-key", TestTokens::privateKeyBase64);
    }
}
