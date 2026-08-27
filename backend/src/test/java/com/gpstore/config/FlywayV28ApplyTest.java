package com.gpstore.config;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

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

    @Test
    @DisplayName("V28 uses extensions.gin_trgm_ops when that is the only opclass - the production failure")
    void doBlockCreatesIndexesWhenGinTrgmOpsIsInExtensions() throws Exception {
        DataSource dataSource = jdbc.getDataSource();
        assumeTrue(dataSource instanceof HikariDataSource, "need Hikari credentials to open a probe database");
        HikariDataSource hikari = (HikariDataSource) dataSource;
        String probeDb = "gpstore_v28_extensions_probe";

        try (Connection admin = dataSource.getConnection(); Statement statement = admin.createStatement()) {
            admin.setAutoCommit(true);
            dropDatabase(statement, probeDb);
            statement.execute("CREATE DATABASE " + probeDb);
        } catch (SQLException e) {
            assumeTrue(false, "CREATE DATABASE not permitted: " + e.getMessage());
            return;
        }

        String probeUrl = hikari.getJdbcUrl().replaceFirst("(/)([^/?]+)(\\?.*)?$", "$1" + probeDb + "$3");
        String v28 = new ClassPathResource("db/migration/V28__search_keyword_trigram_indexes_schema_aware.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        int doAt = v28.indexOf("DO $$");
        assertTrue(doAt >= 0);

        try (Connection probe = DriverManager.getConnection(probeUrl, hikari.getUsername(), hikari.getPassword());
             Statement statement = probe.createStatement()) {
            statement.execute("CREATE SCHEMA extensions");
            statement.execute("CREATE EXTENSION pg_trgm WITH SCHEMA extensions");
            statement.execute("""
                    CREATE TABLE products (
                        id bigint PRIMARY KEY,
                        search_keywords text,
                        subcategory text
                    )
                    """);

            boolean v27Failed = false;
            try {
                statement.execute("""
                        CREATE INDEX IF NOT EXISTS idx_products_search_keywords_trgm
                            ON products USING GIN (search_keywords gin_trgm_ops)
                        """);
            } catch (SQLException e) {
                v27Failed = "42704".equals(e.getSQLState())
                        || (e.getMessage() != null && e.getMessage().contains("gin_trgm_ops"));
            }
            assertTrue(v27Failed, "unqualified gin_trgm_ops must fail when pg_trgm lives in extensions");

            statement.execute(v28.substring(doAt));

            try (ResultSet rs = statement.executeQuery(
                    "SELECT indexdef FROM pg_indexes WHERE indexname = 'idx_products_search_keywords_trgm'")) {
                assertTrue(rs.next());
                String indexDef = rs.getString(1);
                assertTrue(indexDef.contains("extensions.gin_trgm_ops"), indexDef);
            }
        } finally {
            try (Connection admin = dataSource.getConnection(); Statement statement = admin.createStatement()) {
                admin.setAutoCommit(true);
                dropDatabase(statement, probeDb);
            } catch (SQLException ignored) {
                // probe DB is only for this test
            }
        }
    }

    private static void dropDatabase(Statement statement, String name) throws SQLException {
        try {
            statement.execute("DROP DATABASE IF EXISTS " + name + " WITH (FORCE)");
        } catch (SQLException e) {
            statement.execute("DROP DATABASE IF EXISTS " + name);
        }
    }
}
