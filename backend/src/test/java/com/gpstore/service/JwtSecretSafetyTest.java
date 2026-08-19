package com.gpstore.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 12: production must not be able to run on the development JWT
 * secret.
 *
 * That secret is committed to this repository, so an instance signing real
 * tokens with it means anyone who can read the source can mint a token for
 * any customer id and any role - including ADMIN - and every request made
 * with it looks perfectly legitimate in the logs. It is a total
 * authentication bypass that leaves no trace, which is exactly why the
 * failure has to be at startup rather than something to notice later.
 *
 * These construct JwtService directly rather than booting a context: the
 * behaviour under test is the constructor's validation, and asserting it
 * this way keeps the test fast and unambiguous about what triggered the
 * failure.
 */
class JwtSecretSafetyTest {

    private static final long FIFTEEN_MINUTES = 900_000L;
    private static final String REAL_SECRET =
            "a-real-production-secret-that-is-definitely-long-enough-1234567890";

    @Test
    void productionRefusesToStartWithTheDevelopmentFallbackSecret() {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new JwtService(JwtService.DEV_FALLBACK_SECRET, FIFTEEN_MINUTES, true));

        assertTrue(failure.getMessage().contains("development JWT secret"),
                "The failure must name the actual cause so it is fixable from the log alone, was: "
                        + failure.getMessage());
    }

    @Test
    void productionRefusesToStartWithASecretTooShortForHs256() {
        assertThrows(IllegalStateException.class,
                () -> new JwtService("far-too-short", FIFTEEN_MINUTES, true));
    }

    @Test
    void productionRefusesToStartWithNoSecretAtAll() {
        assertThrows(IllegalStateException.class,
                () -> new JwtService(null, FIFTEEN_MINUTES, true));
        assertThrows(IllegalStateException.class,
                () -> new JwtService("   ", FIFTEEN_MINUTES, true));
    }

    @Test
    void productionStartsNormallyWithARealSecret() {
        assertDoesNotThrow(() -> new JwtService(REAL_SECRET, FIFTEEN_MINUTES, true));
    }

    /**
     * The guard must not make local development or CI painful - both run
     * with app.production=false and are expected to keep using the checked-in
     * default. Breaking that would push people toward disabling the check
     * rather than setting a real secret in the one place it matters.
     */
    @Test
    void nonProductionStillAllowsTheDevelopmentSecret() {
        assertDoesNotThrow(
                () -> new JwtService(JwtService.DEV_FALLBACK_SECRET, FIFTEEN_MINUTES, false));
    }
}
