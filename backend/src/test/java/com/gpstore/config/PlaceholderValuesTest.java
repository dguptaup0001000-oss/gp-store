package com.gpstore.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlaceholderValuesTest {

    @Test
    void publishedDefaultsArePlaceholders() {
        assertTrue(PlaceholderValues.isBlankOrPlaceholder("+91XXXXXXXXXX"));
        assertTrue(PlaceholderValues.isBlankOrPlaceholder("support@example.com"));
        assertTrue(PlaceholderValues.isBlankOrPlaceholder("yourstorename@upi"));
        assertTrue(PlaceholderValues.isBlankOrPlaceholder("CHANGE_ME"));
        assertTrue(PlaceholderValues.isBlankOrPlaceholder(""));
        assertTrue(PlaceholderValues.isBlankOrPlaceholder(null));
    }

    @Test
    void realContactsAreKept() {
        assertFalse(PlaceholderValues.isBlankOrPlaceholder("+919876543210"));
        assertFalse(PlaceholderValues.isBlankOrPlaceholder("hello@gpstore.co.in"));
        assertEquals("+919876543210", PlaceholderValues.publicOrEmpty("+919876543210"));
        assertEquals("", PlaceholderValues.publicOrEmpty("support@example.com"));
    }

    @Test
    void secretsRejectChangeMeWithoutTreatingTodoAsASubstringTrap() {
        assertTrue(PlaceholderValues.isSecretPlaceholder("CHANGE_ME_TO_A_LONG_RANDOM_PASSWORD"));
        assertFalse(PlaceholderValues.isSecretPlaceholder("real-db-password-not-a-placeholder"));
        assertFalse(PlaceholderValues.isSecretPlaceholder("high-entropy-value-without-forbidden-markers"));
        assertTrue(PlaceholderValues.isBlankOrPlaceholder("https://placehold.co/600"));
    }
}
