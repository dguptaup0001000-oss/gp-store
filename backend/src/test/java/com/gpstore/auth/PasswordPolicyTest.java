package com.gpstore.auth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PasswordPolicyTest {

    @Test
    void acceptsALetterAndADigit() {
        assertTrue(PasswordPolicy.isAcceptable("Passw0rd!23"));
        assertTrue(PasswordPolicy.isAcceptable("grocery9"));
    }

    @Test
    void rejectsTooShortOrLettersOnlyOrDigitsOnly() {
        assertFalse(PasswordPolicy.isAcceptable("short1"));
        assertFalse(PasswordPolicy.isAcceptable("password"));
        assertFalse(PasswordPolicy.isAcceptable("12345678"));
        assertFalse(PasswordPolicy.isAcceptable(null));
        assertFalse(PasswordPolicy.isAcceptable("abcdefgh"));
    }

    @Test
    void requireAcceptableThrowsOnDenylist() {
        assertThrows(com.gpstore.exception.BadRequestException.class,
                () -> PasswordPolicy.requireAcceptable("password1"));
    }
}
