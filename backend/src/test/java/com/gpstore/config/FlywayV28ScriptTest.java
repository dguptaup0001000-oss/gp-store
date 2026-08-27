package com.gpstore.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V28 is the production fix for V27's unqualified {@code gin_trgm_ops}.
 * These assertions do not need a database: if the script stops looking at
 * {@code pg_opclass} / {@code extensions}, the next deploy dies the same way
 * V27 did.
 */
class FlywayV28ScriptTest {

    @Test
    @DisplayName("V28 picks gin_trgm_ops from pg_opclass, preferring schema extensions")
    void v28IsSchemaAwareAndIdempotent() throws Exception {
        String sql = new ClassPathResource("db/migration/V28__search_keyword_trigram_indexes_schema_aware.sql")
                .getContentAsString(StandardCharsets.UTF_8);

        assertTrue(sql.contains("pg_opclass"), "V28 must look up gin_trgm_ops instead of assuming public");
        assertTrue(sql.contains("extensions"), "production pg_trgm lives in schema extensions");
        assertTrue(sql.contains("CREATE INDEX IF NOT EXISTS idx_products_search_keywords_trgm"));
        assertTrue(sql.contains("CREATE INDEX IF NOT EXISTS idx_products_subcategory_trgm"));
        assertFalse(sql.contains("CREATE EXTENSION"),
                "Do not CREATE EXTENSION pg_trgm here; the production role may not be allowed to");
        assertFalse(sql.contains("ON products USING GIN (search_keywords gin_trgm_ops)"),
                "Unqualified gin_trgm_ops is exactly what failed V27 on production");
    }
}
