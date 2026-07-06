package com.vprok.forms;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Boots the full application context against a throwaway PostgreSQL container,
 * which also proves the Flyway migrations apply cleanly. Skipped automatically
 * on machines without Docker.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class FormStructureBuilderApplicationIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void contextLoadsAndMigrationsApply() {
    }
}
