package com.gpstore.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The guard's whole value is in WHICH settings it refuses, so each one is
 * asserted by name rather than trusting a single representative case.
 */
class SchemaSafetyGuardTest {

    @Test
    @DisplayName("Production refuses to start on any schema-destroying ddl-auto")
    void productionRefusesDataDestroyingModes() {
        for (String destructive : new String[]{"create", "create-drop", "drop", "CREATE-DROP", " Drop "}) {
            IllegalStateException thrown = assertThrows(IllegalStateException.class,
                    () -> new SchemaSafetyGuard(true, destructive).checkSchemaManagementIsSafeForProduction(),
                    "ddl-auto=" + destructive + " drops tables and must not boot in production");

            assertTrue(thrown.getMessage().contains("DDL_AUTO=validate"),
                    "The failure has to say what to set instead, not just that it refused. Was: "
                            + thrown.getMessage());
        }
    }

    @Test
    @DisplayName("Production starts on update - loudly, but it starts")
    void productionStillStartsOnUpdate() {
        // Deliberate: a hard failure here would take a running deployment
        // offline on its next deploy. See the class comment.
        assertDoesNotThrow(() -> new SchemaSafetyGuard(true, "update").checkSchemaManagementIsSafeForProduction());
    }

    @Test
    @DisplayName("Production starts silently on validate and none")
    void productionAcceptsTheSafeModes() {
        assertDoesNotThrow(() -> new SchemaSafetyGuard(true, "validate").checkSchemaManagementIsSafeForProduction());
        assertDoesNotThrow(() -> new SchemaSafetyGuard(true, "none").checkSchemaManagementIsSafeForProduction());
    }

    @Test
    @DisplayName("Non-production is never blocked - local dev and CI build schemas from nothing")
    void developmentIsUnaffected() {
        for (String mode : new String[]{"create", "create-drop", "drop", "update", "validate", "none"}) {
            assertDoesNotThrow(() -> new SchemaSafetyGuard(false, mode).checkSchemaManagementIsSafeForProduction(),
                    "ddl-auto=" + mode + " must stay usable outside production");
        }
    }

    @Test
    @DisplayName("A null or blank setting does not crash the guard itself")
    void toleratesMissingConfiguration() {
        assertDoesNotThrow(() -> new SchemaSafetyGuard(true, null).checkSchemaManagementIsSafeForProduction());
        assertDoesNotThrow(() -> new SchemaSafetyGuard(true, "   ").checkSchemaManagementIsSafeForProduction());
    }
}
