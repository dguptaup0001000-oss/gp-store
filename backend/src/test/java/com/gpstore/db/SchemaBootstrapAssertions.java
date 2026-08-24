package com.gpstore.db;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SchemaBootstrapAssertions {

    private SchemaBootstrapAssertions() {
    }

    /**
     * Every versioned SQL migration on the classpath finished successfully
     * and none are still pending.
     *
     * A synthetic Flyway baseline row at version 1 is allowed: when
     * {@code ddl-auto=update} creates tables first, the schema is non-empty
     * when Flyway runs, and {@code baseline-on-migrate=true} records version 1
     * so that a (non-existent) V1 is not required. Real scripts start at V2.
     */
    static void assertEveryVersionedMigrationSucceeded(Flyway flyway) {
        assertEquals(0, flyway.info().pending().length,
                "Flyway still has pending migrations: " + describe(flyway.info().pending()));

        List<String> appliedSqlVersions = new ArrayList<>();
        for (MigrationInfo info : flyway.info().all()) {
            if (info.getVersion() == null) {
                continue;
            }
            if (info.getState() == MigrationState.BASELINE || isBaseline(info)) {
                continue;
            }
            assertEquals(MigrationState.SUCCESS, info.getState(),
                    () -> "Migration " + info.getScript() + " ended in " + info.getState()
                            + " rather than SUCCESS");
            String version = info.getVersion().getVersion();
            if ("1".equals(version) && isBaseline(info)) {
                continue;
            }
            appliedSqlVersions.add(version);
        }

        assertFalse(appliedSqlVersions.isEmpty(), "No versioned Flyway scripts were applied");

        int start = 0;
        if ("1".equals(appliedSqlVersions.getFirst()) && appliedSqlVersions.size() > 1) {
            start = 1;
        }
        List<String> fromV2 = appliedSqlVersions.subList(start, appliedSqlVersions.size());
        assertEquals("2", fromV2.getFirst(),
                "First applied SQL migration must be V2 (there is no V1). Applied: "
                        + appliedSqlVersions);

        int expected = 2;
        for (String version : fromV2) {
            assertEquals(Integer.toString(expected), version,
                    "Applied Flyway versions are not contiguous from V2: " + appliedSqlVersions);
            expected++;
        }
        assertTrue(expected > 2, "Expected V2 and later to be applied");
    }

    private static boolean isBaseline(MigrationInfo info) {
        String description = info.getDescription() == null ? "" : info.getDescription();
        String type = info.getType() == null ? "" : info.getType().name();
        return type.contains("BASELINE") || description.toLowerCase().contains("baseline");
    }

    private static String describe(MigrationInfo[] infos) {
        List<String> names = new ArrayList<>();
        for (MigrationInfo info : infos) {
            names.add(info.getScript() + " [" + info.getState() + "]");
        }
        return names.toString();
    }
}
