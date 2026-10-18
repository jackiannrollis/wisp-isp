package com.corebalt.isp.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.utility.MountableFile;

/**
 * Base for Testcontainers integration tests: PostgreSQL 17 and RabbitMQ 4, shared by all tests in
 * the JVM. Flyway migrates as the container superuser (the owner); the application connects as
 * {@code isp_app}, which owns nothing, so row-level security is really exercised.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"isp.outbox.relay.interval=1h"})
@ActiveProfiles("test")
public abstract class IntegrationTest {

    /** Throwaway password for the test container only; not a real secret. */
    static final String APP_PASSWORD = "isp_app_test_only";

    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:17.11")
                    .withDatabaseName("isp")
                    .withCopyFileToContainer(
                            MountableFile.forClasspathResource("db/test-roles.sql"),
                            "/docker-entrypoint-initdb.d/00-test-roles.sql");

    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4-management");

    static {
        POSTGRES.start();
        RABBIT.start();
    }

    @DynamicPropertySource
    static void containerProperties(final DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "isp_app");
        registry.add("spring.datasource.password", () -> APP_PASSWORD);
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
        registry.add("spring.rabbitmq.host", RABBIT::getHost);
        registry.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
        registry.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
        registry.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
    }
}
