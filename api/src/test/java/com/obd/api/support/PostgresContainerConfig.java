package com.obd.api.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The Postgres instance every database-backed test runs against.
 *
 * Declared as a bean rather than a {@code @Container} static field on purpose:
 * Spring's test-context cache keeps this context (and therefore the container)
 * alive for the whole test run, so all test classes that import this share one
 * container instead of paying a fresh container start each. {@code @ServiceConnection}
 * points spring.datasource.* at it, so no URL/credentials have to be wired by hand.
 */
@TestConfiguration(proxyBeanMethods = false)
public class PostgresContainerConfig {

    // Pinned to match the version the README tells you to run locally - a test
    // suite that passes on a different major than production is not evidence.
    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
        return new PostgreSQLContainer("postgres:16-alpine");
    }
}
