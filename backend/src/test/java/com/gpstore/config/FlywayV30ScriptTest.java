package com.gpstore.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * EmptyDatabaseBootstrapTest runs Hibernate ddl-auto=update then Flyway.
 * Hibernate creates r2_staging_objects from {@code R2StagingObject} first,
 * so V30 must be IF NOT EXISTS or the schema-migrate job dies the same way
 * V25 would have without that clause.
 */
class FlywayV30ScriptTest {

    @Test
    @DisplayName("V30 is idempotent under Hibernate-first bootstrap")
    void v30UsesIfNotExists() throws Exception {
        String sql = new ClassPathResource("db/migration/V30__r2_staging_objects.sql")
                .getContentAsString(StandardCharsets.UTF_8);

        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS r2_staging_objects"),
                "Hibernate may already have created r2_staging_objects before Flyway");
        assertTrue(sql.contains("CREATE INDEX IF NOT EXISTS idx_r2_staging_objects_created_at"),
                "The created_at index must also tolerate Hibernate having made it");
    }
}
