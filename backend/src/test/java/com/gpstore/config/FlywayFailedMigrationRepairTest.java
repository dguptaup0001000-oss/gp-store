package com.gpstore.config;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The production Flyway failure is a history row, not a missing file: V27
 * recorded {@code success = false}, and {@code validateOnMigrate} then refuses
 * to boot. This test plants that row in a throwaway history table and checks
 * the repair flips it without touching any other version.
 */
@SpringBootTest(properties = {
        "outbox.initial-delay-ms=3600000",
        "outbox.drain-interval-ms=3600000",
        "payment.expiry-initial-delay-ms=3600000",
        "idempotency.cleanup-initial-delay-ms=3600000",
        "otp.cleanup-initial-delay-ms=3600000",
        "delivery.late-flag-initial-delay-ms=3600000"
})
class FlywayFailedMigrationRepairTest {

    private static final String TABLE = "flyway_repair_probe";

    @Autowired
    private DataSource dataSource;

    @Test
    @DisplayName("failed V27 is marked success; other rows are left alone")
    void marksFailedV27AsSuccessAndIgnoresTheRest() throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS " + TABLE);
            statement.execute("""
                    CREATE TABLE %s (
                        installed_rank INTEGER NOT NULL PRIMARY KEY,
                        version VARCHAR(50),
                        description VARCHAR(200) NOT NULL,
                        type VARCHAR(20) NOT NULL,
                        script VARCHAR(1000) NOT NULL,
                        checksum INTEGER,
                        installed_by VARCHAR(100) NOT NULL,
                        installed_on TIMESTAMP NOT NULL DEFAULT now(),
                        execution_time INTEGER NOT NULL,
                        success BOOLEAN NOT NULL
                    )
                    """.formatted(TABLE));
            statement.execute("""
                    INSERT INTO %s
                        (installed_rank, version, description, type, script, checksum,
                         installed_by, execution_time, success)
                    VALUES
                        (26, '26', 'catalog hotpath indexes', 'SQL',
                         'V26__catalog_hotpath_indexes.sql', 1, 'test', 5, true),
                        (27, '27', 'search keyword trigram indexes', 'SQL',
                         'V27__search_keyword_trigram_indexes.sql', -1932722443, 'test', 10, false)
                    """.formatted(TABLE));
        }

        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .table(TABLE)
                .load();
        FlywayFailedMigrationRepair.repair(flyway);

        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(
                     "SELECT version, success FROM " + TABLE + " ORDER BY installed_rank")) {
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals("26", rs.getString("version"));
                assertTrue(rs.getBoolean("success"));
                assertTrue(rs.next());
                assertEquals("27", rs.getString("version"));
                assertTrue(rs.getBoolean("success"), "failed V27 must be marked success so migrate can reach V28");
            }
        }

        FlywayFailedMigrationRepair.repair(flyway);

        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            try (ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM " + TABLE + " WHERE success = false")) {
                assertTrue(rs.next());
                assertEquals(0, rs.getInt(1));
            }
            statement.execute("DROP TABLE IF EXISTS " + TABLE);
        }
    }

    @Test
    @DisplayName("repair is a no-op when the history table does not exist yet")
    void missingHistoryTableIsIgnored() {
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .table("flyway_repair_probe_missing")
                .load();
        FlywayFailedMigrationRepair.repair(flyway);
    }

    @Test
    void quoteIdentRejectsAnythingThatIsNotAPostgresIdentifier() {
        assertThrows(IllegalArgumentException.class,
                () -> FlywayFailedMigrationRepair.quoteIdent("flyway_schema_history;drop"));
        assertEquals("flyway_schema_history",
                FlywayFailedMigrationRepair.quoteIdent("flyway_schema_history"));
        assertEquals("public.flyway_schema_history",
                FlywayFailedMigrationRepair.qualify("public", "flyway_schema_history"));
    }
}
