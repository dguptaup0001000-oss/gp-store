package com.gpstore.config;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.zip.CRC32;

/**
 * Makes Flyway skip V27's SQL so V28 can create the indexes.
 *
 * WHAT HAPPENED. V27 uses unqualified {@code gin_trgm_ops}. Production
 * Postgres (Supabase dump) has {@code pg_trgm} in schema {@code extensions},
 * so that operator class is {@code extensions.gin_trgm_ops}. V27 fails with
 * SQLState 42704. PostgreSQL runs each Flyway script in a transaction, so
 * the failed history row is rolled back with the CREATE INDEX. The next
 * boot sees V27 as <em>pending</em>, not failed. Updating
 * {@code success = false} matches zero rows; Flyway runs V27 again; deploy
 * rolls back to {@code f229fd9}. That is what #111 did in production.
 *
 * FIX. If version 27 is missing from history, insert a successful row with
 * V27's current checksum so Flyway will not execute the file. If a failed
 * row did persist, mark it successful. V28 then creates the indexes with a
 * schema-aware operator class. V27 on disk is not edited.
 *
 * WHY NOT {@code flyway.repair()}. Repair also realigns checksums of every
 * applied migration and would hide an accidental edit of V2–V26.
 */
public final class FlywayFailedMigrationRepair {

    static final String FAILED_VERSION = "27";
    static final String V27_SCRIPT = "V27__search_keyword_trigram_indexes.sql";
    static final String V27_DESCRIPTION = "search keyword trigram indexes";
    static final String V27_RESOURCE = "/db/migration/" + V27_SCRIPT;

    private static final Logger log = LoggerFactory.getLogger(FlywayFailedMigrationRepair.class);

    private FlywayFailedMigrationRepair() {
    }

    public static void repair(Flyway flyway) {
        repair(flyway, null);
    }

    public static void repair(Flyway flyway, DataSource fallbackDataSource) {
        if (flyway == null) {
            throw new IllegalStateException("Flyway is required to repair V" + FAILED_VERSION);
        }
        Configuration config = flyway.getConfiguration();
        DataSource dataSource = config.getDataSource();
        if (dataSource == null) {
            dataSource = fallbackDataSource;
        }
        if (dataSource == null) {
            throw new IllegalStateException(
                    "No DataSource available to repair Flyway V" + FAILED_VERSION
                            + " (Flyway config and fallback were both null)");
        }
        String preferredSchema = historySchema(config);
        String table = config.getTable();
        if (table == null || table.isBlank()) {
            table = "flyway_schema_history";
        }

        try (Connection connection = dataSource.getConnection()) {
            String schema = resolveSchema(connection, preferredSchema, table);
            if (schema == null) {
                log.info("Flyway history table {}.{} is absent; nothing to repair.", preferredSchema, table);
                return;
            }
            String qualified = qualify(schema, table);
            boolean autoCommit = connection.getAutoCommit();

            int updatedFailed;
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE " + qualified + " SET success = true WHERE version = ? AND success = false")) {
                ps.setString(1, FAILED_VERSION);
                updatedFailed = ps.executeUpdate();
            }

            int insertedSkip = 0;
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO " + qualified + " ("
                            + "installed_rank, version, description, type, script, checksum, "
                            + "installed_by, execution_time, success) "
                            + "SELECT COALESCE((SELECT MAX(installed_rank) FROM " + qualified + "), 0) + 1, "
                            + "?, ?, 'SQL', ?, ?, current_user, 0, true "
                            + "WHERE NOT EXISTS (SELECT 1 FROM " + qualified + " WHERE version = ?)")) {
                ps.setString(1, FAILED_VERSION);
                ps.setString(2, V27_DESCRIPTION);
                ps.setString(3, V27_SCRIPT);
                ps.setInt(4, v27Checksum());
                ps.setString(5, FAILED_VERSION);
                insertedSkip = ps.executeUpdate();
            }

            if (!autoCommit) {
                connection.commit();
            }

            if (updatedFailed > 0 || insertedSkip > 0) {
                log.warn("Flyway V{} repair on {}: markedFailedSuccess={}, insertedSkipRow={}. "
                                + "V27 SQL will not run; V28 creates schema-aware trigram indexes.",
                        FAILED_VERSION, qualified, updatedFailed, insertedSkip);
            } else {
                log.info("Flyway V{} already present as success on {}; no repair needed.",
                        FAILED_VERSION, qualified);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to repair Flyway V" + FAILED_VERSION + " history", e);
        }
    }

    static int v27Checksum() {
        return flywayChecksum(V27_RESOURCE);
    }

    static int flywayChecksum(String classpath) {
        InputStream in = FlywayFailedMigrationRepair.class.getResourceAsStream(classpath);
        if (in == null) {
            throw new IllegalStateException("Missing classpath resource " + classpath);
        }
        CRC32 crc = new CRC32();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                crc.update(line.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            throw new IllegalStateException("Could not checksum " + classpath, e);
        }
        return (int) crc.getValue();
    }

    static String historySchema(Configuration config) {
        String def = config.getDefaultSchema();
        if (def != null && !def.isBlank()) {
            return def.trim();
        }
        String[] schemas = config.getSchemas();
        if (schemas != null) {
            for (String schema : schemas) {
                if (schema != null && !schema.isBlank()) {
                    return schema.trim();
                }
            }
        }
        return "public";
    }

    static String qualify(String schema, String table) {
        return quoteIdent(schema) + "." + quoteIdent(table);
    }

    static String quoteIdent(String ident) {
        if (ident == null || !ident.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("Refusing to interpolate Flyway identifier: " + ident);
        }
        return ident;
    }

    private static String resolveSchema(Connection connection, String preferredSchema, String table)
            throws SQLException {
        if (tableExists(connection, preferredSchema, table)) {
            return preferredSchema;
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT table_schema FROM information_schema.tables WHERE table_name = ? "
                        + "ORDER BY CASE table_schema WHEN 'public' THEN 0 ELSE 1 END LIMIT 1")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString(1);
                }
            }
        }
        return null;
    }

    private static boolean tableExists(Connection connection, String schema, String table)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT 1 FROM information_schema.tables WHERE table_schema = ? AND table_name = ?")) {
            ps.setString(1, schema);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }
}
