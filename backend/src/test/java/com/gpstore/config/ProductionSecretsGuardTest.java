package com.gpstore.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductionSecretsGuardTest {

    @Test
    void productionRefusesPlaceholderDatabasePassword() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> boot(true, "CHANGE_ME", "redis-real-password-long", "+919876543210", "", "shop@gpstore.co.in"));
        assertTrue(ex.getMessage().contains("DB_PASSWORD"));
    }

    @Test
    void productionRefusesPlaceholderRedisPassword() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> boot(true, "db-real-password-long", "CHANGE_ME", "+919876543210", "", ""));
        assertTrue(ex.getMessage().contains("REDIS_PASSWORD"));
    }

    @Test
    void productionRefusesFakeSupportContacts() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> boot(true, "db-real-password-long", "redis-real-password-long",
                        "+91XXXXXXXXXX", "+91XXXXXXXXXX", "support@example.com"));
        assertTrue(ex.getMessage().toLowerCase().contains("support"));
    }

    @Test
    void productionStartsWithRealSecretsAndOneRealContact() {
        assertDoesNotThrow(() -> boot(true, "db-real-password-long", "redis-real-password-long",
                "+919876543210", "", ""));
    }

    @Test
    void nonProductionAllowsEmptyPlaceholders() {
        assertDoesNotThrow(() -> boot(false, "", "", "+91XXXXXXXXXX", "", "support@example.com"));
    }

    private static void boot(
            boolean production,
            String db,
            String redis,
            String phone,
            String whatsapp,
            String email) {
        new ProductionSecretsGuard(production, db, redis, phone, whatsapp, email)
                .refusePlaceholdersInProduction();
    }
}
