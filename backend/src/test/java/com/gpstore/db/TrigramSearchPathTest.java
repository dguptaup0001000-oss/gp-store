package com.gpstore.db;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * EVERY POOLED CONNECTION CAN RESOLVE pg_trgm, WHEREVER IT IS INSTALLED.
 *
 * <p>WHAT THIS IS FOR. Production's pg_trgm is a Supabase dump living in
 * schema {@code extensions}. The default search_path does not include it, so
 * {@code %} and {@code similarity()} did not resolve and every
 * {@code /api/products/search/instant} answered HTTP 500 - while a developer
 * database and CI, which both have pg_trgm in {@code public}, passed
 * everything. The first production smoke run found it in 46 seconds.
 *
 * <p>THE FIX IS CONFIGURATION, NOT A MANUAL STEP. Hikari's
 * {@code connection-init-sql} names the extension schema on every connection
 * the pool opens. {@code ALTER DATABASE ... SET search_path} would do the same
 * thing, but only on a database somebody remembered to run it against - and a
 * restored backup, a new environment or a rebuilt box silently loses it. A
 * schema that does not exist is ignored by PostgreSQL, so naming
 * {@code extensions} is inert where pg_trgm is in {@code public}.
 *
 * <p>{@code ProductBrowseRepository} still degrades to ILIKE ranking if the
 * operator is somehow unreachable, so search cannot 500 either way. This test
 * is about the other half: that the degradation should never be NEEDED, and
 * that real similarity ranking is what production actually gets.
 */
@SpringBootTest(properties = {
        "outbox.initial-delay-ms=3600000",
        "outbox.drain-interval-ms=3600000",
        "payment.expiry-initial-delay-ms=3600000",
        "idempotency.cleanup-initial-delay-ms=3600000",
        "otp.cleanup-initial-delay-ms=3600000",
        "delivery.late-flag-initial-delay-ms=3600000"
})
@DisplayName("pg_trgm resolves on every pooled connection")
class TrigramSearchPathTest {

    @Autowired private JdbcTemplate jdbc;

    private static final Path PRODUCTION_PROPERTIES =
            Path.of("src/main/resources/application.properties");

    @Test
    @DisplayName("the connection this test is holding can use the trigram operator")
    void theOperatorResolvesOnARealPooledConnection() {
        // Not "is pg_trgm installed" - "does it resolve unqualified, here,
        // now". That is the question production answered no to.
        Boolean visible = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM pg_operator o
                     WHERE o.oprname = '%'
                       AND o.oprleft = 'text'::regtype
                       AND o.oprright = 'text'::regtype
                       AND pg_catalog.pg_operator_is_visible(o.oid))
                """, Boolean.class);
        assertTrue(Boolean.TRUE.equals(visible),
                "the trigram operator does not resolve on a pooled connection, so instant search "
                        + "falls back to ILIKE ranking and fuzzy matching is silently off. Check "
                        + "that connection-init-sql still names the extension schema.");
    }

    @Test
    @DisplayName("similarity() ranks, so fuzzy search is real and not an ILIKE fallback")
    void similarityActuallyRanks() {
        // The end the search_path exists for. A typo must score above an
        // unrelated word, which ILIKE cannot express at all.
        Double close = jdbc.queryForObject(
                "SELECT similarity('dal', 'dhal')", Double.class);
        Double unrelated = jdbc.queryForObject(
                "SELECT similarity('dal', 'shampoo')", Double.class);
        assertNotNull(close);
        assertNotNull(unrelated);
        assertTrue(close > unrelated,
                "similarity() did not rank a typo above an unrelated word (" + close + " vs "
                        + unrelated + "), so trigram ranking is not doing anything");
        assertTrue(close > 0.0, "a one-letter typo scored zero similarity: " + close);
    }

    @Test
    @DisplayName("the shipped configuration names the extension schema")
    void theSearchPathShips() throws IOException {
        String production = Files.readString(PRODUCTION_PROPERTIES);
        assertTrue(production.contains("set_config('search_path'"),
                "connection-init-sql no longer sets search_path. Production's pg_trgm lives in "
                        + "schema 'extensions' and without this every instant search 500s.");
        assertTrue(production.contains("extensions"),
                "the search_path no longer names the 'extensions' schema, which is where "
                        + "production's pg_trgm actually is");

        int searchPath = production.indexOf("set_config('search_path'");
        int publicAt = production.indexOf("public", searchPath);
        int extensionsAt = production.indexOf("extensions", searchPath);
        assertTrue(publicAt > 0 && extensionsAt > publicAt,
                "'public' must come FIRST in search_path so table resolution is unchanged; "
                        + "putting the extension schema ahead of it lets an extension shadow a "
                        + "table name");
    }
}
