package com.gpstore.config;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Clears a failed Flyway V27 row <em>before</em> {@code migrate()} so V28 can
 * run.
 *
 * WHAT HAPPENED. V27 creates GIN trigram indexes with the unqualified
 * operator class {@code gin_trgm_ops}. That name resolves on stock Postgres
 * (CI, V5 {@code CREATE EXTENSION pg_trgm} into {@code public}). Production
 * is a Supabase dump: {@code pg_trgm} lives in schema {@code extensions},
 * and the operator class is {@code extensions.gin_trgm_ops}. The live
 * name/brand indexes already use that qualified name. V27 therefore died
 * with SQLState 42704, Flyway recorded {@code success = false}, and every
 * later deploy rolled back to {@code f229fd9}.
 *
 * WHY THIS CANNOT BE A FLYWAY CALLBACK. {@code validateOnMigrate} (the
 * default) runs <em>inside</em> {@code migrate()} and treats a failed history
 * row as a validation error. A {@code BEFORE_MIGRATE} callback never fires.
 * The failed row has to be repaired on the DataSource, then {@code migrate()}
 * called.
 *
 * WHY NOT EDIT V27. Production already stored V27's checksum on the failed
 * row. The moment this repair marks that row successful, that checksum is
 * the production checksum of V27. Changing the file would fail the next
 * boot the same way a comment-only edit of V19 did. V27 is left byte-for-byte
 * alone. V28 creates the indexes with a schema-aware operator class.
 *
 * WHY NOT {@code flyway.repair()}. Repair also realigns checksums of every
 * applied migration. That would hide an accidental edit of V2–V26. This
 * class only flips {@code success} on version 27.
 *
 * Idempotent: no-op when the history table is missing (empty database) or
 * when V27 is absent / already successful.
 */
public final class FlywayFailedMigrationRepair {

    static final String FAILED_VERSION = "27";

    private static final Logger log = LoggerFactory.getLogger(FlywayFailedMigrationRepair.class);

    private FlywayFailedMigrationRepair() {
    }

    public static void repair(Flyway flyway) {
        if (flyway == null) {
            return;
        }
        Configuration config = flyway.getConfiguration();
        DataSource dataSource = config.getDataSource();
        if (dataSource == null) {
            return;
        }
        String schema = historySchema(config);
        String table = config.getTable();
        if (table == null || table.isBlank()) {
            table = "flyway_schema_history";
        }
        String qualified = qualify(schema, table);

        try (Connection connection = dataSource.getConnection()) {
            if (!historyTableExists(connection, schema, table)) {
                return;
            }
            boolean autoCommit = connection.getAutoCommit();
            int updated;
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE " + qualified + " SET success = true WHERE version = ? AND success = false")) {
                ps.setString(1, FAILED_VERSION);
                updated = ps.executeUpdate();
            }
            if (!autoCommit) {
                connection.commit();
            }
            if (updated > 0) {
                log.warn("Marked failed Flyway V{} as success ({} row(s)) so V28 can create "
                                + "schema-aware search_keywords/subcategory trigram indexes. "
                                + "V27 itself is unchanged; see V28__search_keyword_trigram_indexes_schema_aware.sql.",
                        FAILED_VERSION, updated);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "Failed to repair Flyway V" + FAILED_VERSION + " history on " + qualified, e);
        }
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

    private static boolean historyTableExists(Connection connection, String schema, String table)
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
