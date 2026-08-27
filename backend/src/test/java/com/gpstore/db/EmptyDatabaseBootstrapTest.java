package com.gpstore.db;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Empty database, production-like Flyway ON, Hibernate still allowed to
 * create tables ({@code ddl-auto=update}).
 *
 * THAT PAIRING IS THE ONLY WAY A FRESH DATABASE BOOTS. Versioned scripts
 * start at V2 and assume domain tables already exist. {@code FlywayAfterSchemaConfig}
 * defers Flyway until after Hibernate when ddl-auto is update, which is what
 * this test exercises.
 *
 * Excluded from the default {@code ./mvnw verify} job (see pom.xml
 * {@code schema-bootstrap} tag). The {@code schema-migrate} GitHub Actions job
 * runs it against a clean Postgres, then runs
 * {@link ProductionSchemaValidateTest} on the same database.
 */
@Tag("schema-bootstrap")
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=update",
        "spring.flyway.enabled=true",
        "outbox.initial-delay-ms=3600000",
        "outbox.drain-interval-ms=3600000",
        "payment.expiry-initial-delay-ms=3600000",
        "idempotency.cleanup-initial-delay-ms=3600000",
        "otp.cleanup-initial-delay-ms=3600000",
        "delivery.late-flag-initial-delay-ms=3600000"
})
class EmptyDatabaseBootstrapTest {

    @Autowired
    private Flyway flyway;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("context starts on an empty database with Flyway after Hibernate")
    void contextStartsAndFlywayAppliesEveryScript() {
        SchemaBootstrapAssertions.assertEveryVersionedMigrationSucceeded(flyway);

        assertTrue(tableExists("orders"), "Hibernate must create domain tables before V2");
        assertTrue(tableExists("products"));
        assertTrue(tableExists("shedlock"), "V3 creates shedlock");
        assertTrue(tableExists("outbox_events"), "V9 creates outbox_events");
        assertTrue(tableExists("delivery_pricing_settings"), "V21 creates delivery_pricing_settings");
        assertTrue(tableExists("password_reset_tokens"), "V24 creates password_reset_tokens");
        assertTrue(tableExists("ops_backup_runs"), "V25 creates ops_backup_runs");
        Integer sellableIdx = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pg_indexes WHERE indexname = 'idx_product_variants_sellable'",
                Integer.class);
        assertEquals(1, sellableIdx, "V26 creates idx_product_variants_sellable");
        Integer searchKwIdx = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pg_indexes WHERE indexname = 'idx_products_search_keywords_trgm'",
                Integer.class);
        assertEquals(1, searchKwIdx, "V27/V28 create idx_products_search_keywords_trgm");
        Integer freeDel = jdbc.queryForObject(
                "SELECT COUNT(*) FROM coupons WHERE coupon_code = 'FREEDEL10' AND discount_type = 'DELIVERY_FLAT'",
                Integer.class);
        assertEquals(1, freeDel, "V29 seeds FREEDEL10 as a delivery coupon");
        assertTrue(sequenceExists("order_number_seq"), "V6 creates order_number_seq");

        Integer trigram = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pg_extension WHERE extname = 'pg_trgm'",
                Integer.class);
        assertEquals(1, trigram, "V5 must install pg_trgm");
    }

    private boolean tableExists(String table) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables "
                        + "WHERE table_schema = current_schema() AND table_name = ?",
                Integer.class,
                table);
        return count != null && count == 1;
    }

    private boolean sequenceExists(String sequence) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace "
                        + "WHERE c.relkind = 'S' AND n.nspname = current_schema() AND c.relname = ?",
                Integer.class,
                sequence);
        return count != null && count == 1;
    }
}
