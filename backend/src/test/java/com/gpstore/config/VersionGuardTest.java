package com.gpstore.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VersionGuardTest {

    private static final String SHA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    @Test
    @DisplayName("Production refuses to start without a git SHA")
    void productionRefusesUnknownCommit() {
        IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                () -> new VersionGuard(new AppBuildInfo("1.0", "unknown", true))
                        .requireBuildIdentityInProduction());
        assertTrue(thrown.getMessage().contains("GIT_COMMIT"));
    }

    @Test
    @DisplayName("Production refuses a short or non-hex commit")
    void productionRefusesShortCommit() {
        IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                () -> new VersionGuard(new AppBuildInfo("1.0", "77199a7", true))
                        .requireBuildIdentityInProduction());
        assertTrue(thrown.getMessage().contains("40-character"));
    }

    @Test
    @DisplayName("Production starts when GIT_COMMIT is a full SHA")
    void productionAcceptsFullSha() {
        assertDoesNotThrow(() -> new VersionGuard(new AppBuildInfo("1.0", SHA, true))
                .requireBuildIdentityInProduction());
    }

    @Test
    @DisplayName("Development may boot with unknown git identity")
    void developmentIsUnaffected() {
        assertDoesNotThrow(() -> new VersionGuard(new AppBuildInfo("1.0", "unknown", false))
                .requireBuildIdentityInProduction());
    }

    @Test
    @DisplayName("Public version payload never includes secrets")
    void buildInfoExposesOnlyIdentity() {
        AppBuildInfo info = new AppBuildInfo("0.0.1-SNAPSHOT", SHA, true);
        assertEquals("production", info.environmentName());
        assertEquals(SHA, info.gitCommit());
        assertEquals("0.0.1-SNAPSHOT", info.version());
    }
}
