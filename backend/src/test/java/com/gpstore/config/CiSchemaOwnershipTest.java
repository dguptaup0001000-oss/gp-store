package com.gpstore.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CiSchemaOwnershipTest {

    @Test
    @DisplayName("verify job uses ddl-auto=validate and Flyway on")
    void buildAndTestUsesValidateAndFlyway() throws Exception {
        Path ci = Path.of("../.github/workflows/ci.yml");
        if (!Files.exists(ci)) {
            ci = Path.of(".github/workflows/ci.yml");
        }
        assertTrue(Files.exists(ci), "ci.yml not found");
        String text = Files.readString(ci);
        assertTrue(text.contains("DDL_AUTO: validate"),
                "build-and-test verify must not use Hibernate auto-DDL");
        assertTrue(text.contains("FLYWAY_ENABLED: 'true'"),
                "build-and-test verify must run Flyway");
        assertTrue(text.contains("Bootstrap schema with Flyway"),
                "gpstore_test must be bootstrapped before validate");
    }

    @Test
    @DisplayName("test overlay defaults to validate so a local mvn test cannot invent columns")
    void testOverlayDefaultsToValidate() throws Exception {
        Path overlay = Path.of("src/test/resources/config/application.properties");
        assertTrue(Files.exists(overlay), "test overlay not found — run from backend/");
        String text = Files.readString(overlay);
        assertTrue(text.contains("spring.jpa.hibernate.ddl-auto=${DDL_AUTO:validate}"));
        assertTrue(text.contains("spring.flyway.enabled=${FLYWAY_ENABLED:true}"));
    }
}
