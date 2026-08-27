package com.gpstore.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Runs the V28 DO block against the same Postgres the default CI job uses
 * (pg_trgm in {@code public}, Hibernate-created {@code products}). schema-migrate
 * already applies V28 via Flyway; this is the proof the SQL is valid even
 * when Flyway is off.
 */
@SpringBootTest(properties = {
        "outbox.initial-delay-ms=3600000",
        "outbox.drain-interval-ms=3600000",
        "payment.expiry-initial-delay-ms=3600000",
        "idempotency.cleanup-initial-delay-ms=3600000",
        "otp.cleanup-initial-delay-ms=3600000",
        "delivery.late-flag-initial-delay-ms=3600000"
})
class FlywayV28ApplyTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("V28 DO block creates the search_keywords trigram index on stock Postgres")
    void doBlockCreatesIndexesWhenGinTrgmOpsIsInPublic() throws Exception {
        Integer opclass = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pg_opclass oc JOIN pg_am am ON am.oid = oc.opcmethod "
                        + "WHERE oc.opcname = 'gin_trgm_ops' AND am.amname = 'gin'",
                Integer.class);
        assumeTrue(opclass != null && opclass > 0, "pg_trgm is required to exercise V28");

        String sql = new ClassPathResource("db/migration/V28__search_keyword_trigram_indexes_schema_aware.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        int doAt = sql.indexOf("DO $$");
        assertTrue(doAt >= 0, "V28 must contain a DO block");
        jdbc.execute(sql.substring(doAt));

        Integer searchKw = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pg_indexes WHERE indexname = 'idx_products_search_keywords_trgm'",
                Integer.class);
        Integer subcategory = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pg_indexes WHERE indexname = 'idx_products_subcategory_trgm'",
                Integer.class);
        assertEquals(1, searchKw);
        assertEquals(1, subcategory);
    }
}
