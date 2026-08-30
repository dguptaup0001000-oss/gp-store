package com.gpstore.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Bootstrap-only. After Hibernate {@code ddl-auto=update} creates entity
 * tables and before deferred Flyway runs, drop tables whose shape is
 * defined by a versioned script so {@code CREATE TABLE IF NOT EXISTS}
 * cannot preserve Hibernate's inferred DDL.
 *
 * Production must never set {@code gpstore.flyway.reset-owned-tables}.
 * Only the empty-database bootstrap test enables it.
 */
@Component
@ConditionalOnProperty(name = "gpstore.flyway.reset-owned-tables", havingValue = "true")
public class FlywayOwnedTableReset implements SmartInitializingSingleton, Ordered {

    private static final Logger log = LoggerFactory.getLogger(FlywayOwnedTableReset.class);

    static final List<String> TABLES = List.of("r2_staging_objects");

    private final JdbcTemplate jdbc;

    public FlywayOwnedTableReset(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void afterSingletonsInstantiated() {
        for (String table : TABLES) {
            jdbc.execute("DROP TABLE IF EXISTS " + table + " CASCADE");
        }
        log.info("Dropped Hibernate-created Flyway-owned tables so versioned SQL creates them: {}", TABLES);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
