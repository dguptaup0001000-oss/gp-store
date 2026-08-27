package com.gpstore.config;

import org.flywaydb.core.Flyway;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * Production boot path ({@code ddl-auto=validate}): Spring Boot's own
 * {@code FlywayMigrationInitializer} runs this strategy instead of calling
 * {@code flyway.migrate()} directly.
 *
 * {@link FlywayAfterSchemaConfig} replaces that initializer with a no-op when
 * Hibernate owns the schema, and its deferred migrator invokes the same
 * strategy so both orderings skip broken V27 before migrate.
 *
 * The Spring {@link DataSource} is passed in because Flyway 11's own
 * configuration DataSource has been null in some Boot wirings; a silent
 * no-op there is how V27 ran again after #111.
 */
@Configuration
@ConditionalOnProperty(name = "spring.flyway.enabled", havingValue = "true", matchIfMissing = true)
public class FlywayRepairAndMigrateConfig {

    @Bean
    public FlywayMigrationStrategy flywayMigrationStrategy(DataSource dataSource) {
        return flyway -> repairAndMigrate(flyway, dataSource);
    }

    static void repairAndMigrate(Flyway flyway, DataSource dataSource) {
        FlywayFailedMigrationRepair.repair(flyway, dataSource);
        flyway.migrate();
    }
}
