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

    /**
     * ONLY TABLES WHOSE VERSIONED SCRIPT CARRIES SOMETHING HIBERNATE DOES NOT.
     *
     * <p>A migration's {@code CREATE TABLE IF NOT EXISTS} is a no-op against a
     * table Hibernate has already made, so every CHECK constraint declared
     * inside it is silently absent on a fresh database. V55 catches its own
     * case - its VERIFY block raises "nothing stops a session that ends before
     * it starts" - which is how the two hours tables were found.
     *
     * <p>DELIBERATELY NOT EVERY TABLE THE MIGRATIONS CREATE. Thirty-five of
     * them are created by both Hibernate and a script; dropping all of them
     * CASCADE would take the foreign keys of surviving tables with it and
     * nothing would put those back, since Hibernate has already run. This list
     * grows one entry at a time, each because something failed without it.
     */
    static final List<String> TABLES = List.of(
            "r2_staging_objects",
            // V55: CONSTRAINT ck_shop_hours_day and ck_shop_hours_order live
            // inside its CREATE TABLE, and its own VERIFY block refuses to
            // let the migration pass without them.
            "shop_hours_override",
            "shop_business_hours");

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
