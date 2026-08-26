package com.gpstore.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/**
 * Production boot must not run on empty, CHANGE_ME, or published placeholder
 * secrets. JWT has its own guard in JwtService; this covers the rest of the
 * values a mis-copied .env.example would leave in place.
 *
 * Optional integrations (Cashfree, Firebase, Cloudinary, MSG91) stay
 * fail-closed at use-time rather than blocking boot: a shop can take COD
 * orders without them. Database, Redis, and support contacts cannot.
 */
@Component
public class ProductionSecretsGuard {

    private final boolean production;
    private final String dbPassword;
    private final String redisPassword;
    private final String supportPhone;
    private final String supportWhatsapp;
    private final String supportEmail;

    public ProductionSecretsGuard(
            @Value("${app.production:false}") boolean production,
            @Value("${spring.datasource.password:}") String dbPassword,
            @Value("${spring.data.redis.password:}") String redisPassword,
            @Value("${store.support-phone:}") String supportPhone,
            @Value("${store.support-whatsapp:}") String supportWhatsapp,
            @Value("${store.support-email:}") String supportEmail) {
        this.production = production;
        this.dbPassword = dbPassword;
        this.redisPassword = redisPassword;
        this.supportPhone = supportPhone;
        this.supportWhatsapp = supportWhatsapp;
        this.supportEmail = supportEmail;
    }

    @PostConstruct
    public void refusePlaceholdersInProduction() {
        if (!production) {
            return;
        }
        if (PlaceholderValues.isSecretPlaceholder(dbPassword)) {
            throw new IllegalStateException(
                    "Refusing to start in production: DB_PASSWORD is missing or a placeholder. "
                            + "Set a real database password on the VPS.");
        }
        if (PlaceholderValues.isSecretPlaceholder(redisPassword)) {
            throw new IllegalStateException(
                    "Refusing to start in production: REDIS_PASSWORD is missing or a placeholder. "
                            + "Set a real Redis password on the VPS.");
        }
        if (PlaceholderValues.isBlankOrPlaceholder(supportPhone)
                && PlaceholderValues.isBlankOrPlaceholder(supportWhatsapp)
                && PlaceholderValues.isBlankOrPlaceholder(supportEmail)) {
            throw new IllegalStateException(
                    "Refusing to start in production: STORE_SUPPORT_PHONE, STORE_SUPPORT_WHATSAPP, "
                            + "and STORE_SUPPORT_EMAIL are missing or placeholders. "
                            + "Set at least one real contact so customers are not shown fake numbers.");
        }
        rejectIfPlaceholder("STORE_SUPPORT_PHONE", supportPhone);
        rejectIfPlaceholder("STORE_SUPPORT_WHATSAPP", supportWhatsapp);
        rejectIfPlaceholder("STORE_SUPPORT_EMAIL", supportEmail);
    }

    /**
     * A field that is set must be real. Empty is allowed only when another
     * contact channel is real (already required above). A mix of a real phone
     * and support@example.com would still leak a fake email.
     */
    private static void rejectIfPlaceholder(String name, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        if (PlaceholderValues.isBlankOrPlaceholder(value)) {
            throw new IllegalStateException(
                    "Refusing to start in production: " + name
                            + " is a placeholder. Set a real value or leave it blank.");
        }
    }
}
