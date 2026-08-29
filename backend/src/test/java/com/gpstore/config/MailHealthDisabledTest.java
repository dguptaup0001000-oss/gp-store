package com.gpstore.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Adding spring-boot-starter-mail made /v1/actuator/health return 503 in CI:
 * JavaMailSender is created for the empty SMTP_HOST default, the mail
 * HealthIndicator fails to connect, and the public health endpoint used by
 * Traefik goes DOWN. AccessDeniedStatusTest.publicHealthStaysOpen caught it.
 */
class MailHealthDisabledTest {

    private static final Path PROPERTIES = Path.of("src/main/resources/application.properties");

    @Test
    @DisplayName("mail health indicator stays off so SMTP cannot take the shop out of rotation")
    void mailHealthIsDisabled() throws Exception {
        assertTrue(Files.exists(PROPERTIES), "application.properties not found — run from backend/");
        String text = Files.readString(PROPERTIES);
        assertTrue(text.contains("management.health.mail.enabled=false"),
                "Mail health must stay disabled; SMTP is optional and OTP already fails closed");
    }
}
