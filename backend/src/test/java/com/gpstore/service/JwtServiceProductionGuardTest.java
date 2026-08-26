package com.gpstore.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JwtServiceProductionGuardTest {

    private static final String REAL =
            "real-production-jwt-secret-value-at-least-thirty-two-bytes-long";

    @Test
    void productionRefusesChangeMe() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new JwtService("CHANGE_ME_TO_A_RANDOM_64_PLUS_CHARACTER_SECRET", 3_600_000, true));
        assertTrue(ex.getMessage().contains("JWT"));
    }

    @Test
    void productionRefusesPublishedDevFallback() {
        assertThrows(IllegalStateException.class,
                () -> new JwtService(JwtService.DEV_FALLBACK_SECRET, 3_600_000, true));
    }

    @Test
    void productionAcceptsALongRandomSecret() {
        assertDoesNotThrow(() -> new JwtService(REAL, 3_600_000, true));
    }

    @Test
    void nonProductionStillAllowsTheDevFallback() {
        assertDoesNotThrow(() -> new JwtService(JwtService.DEV_FALLBACK_SECRET, 3_600_000, false));
    }
}
