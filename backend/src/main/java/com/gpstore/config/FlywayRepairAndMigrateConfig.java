package com.gpstore.config;

import org.flywaydb.core.Flyway;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Production boot path ({@code ddl-auto=validate}): Spring Boot's own
 * {@code FlywayMigrationInitializer} runs this strategy instead of calling
 * {@code flyway.migrate()} directly.
 *
 * {@link FlywayAfterSchemaConfig} replaces that initializer with a no-op when
 * Hibernate owns the schema, and its deferred migrator invokes the same
 * strategy so both orderings repair failed V27 before migrate.
 */
@Configuration
@ConditionalOnProperty(name = "spring.flyway.enabled", havingValue = "true", matchIfMissing = true)
public class FlywayRepairAndMigrateConfig {

    @Bean
    public FlywayMigrationStrategy flywayMigrationStrategy() {
        return FlywayRepairAndMigrateConfig::repairAndMigrate;
    }

    static void repairAndMigrate(Flyway flyway) {
        FlywayFailedMigrationRepair.repair(flyway);
        flyway.migrate();
    }
}
