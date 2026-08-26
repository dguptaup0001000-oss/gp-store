package com.gpstore.auth;

import com.gpstore.exception.BadRequestException;

import java.util.Locale;
import java.util.Set;

/**
 * Passwords accepted by this shop.
 *
 * Length 10 is the floor for any NEW password (register, change, reset).
 * Existing accounts with shorter passwords still sign in; they are asked
 * for 10+ only when they next set a password. A letter and a digit, 10–128
 * characters, and a short denylist of the passwords attackers try first.
 */
public final class PasswordPolicy {

    public static final int MIN_LENGTH = 10;
    public static final int MAX_LENGTH = 128;

    public static final String MESSAGE =
            "Password must be 10–128 characters and contain at least one letter and one number.";

    private static final Set<String> DENYLIST = Set.of(
            "password",
            "password1",
            "password123",
            "12345678",
            "123456789",
            "1234567890",
            "qwertyui",
            "qwerty123",
            "abcdefgh",
            "letmein1",
            "welcome1",
            "admin123",
            "gpstore1",
            "gpstore12"
    );

    private PasswordPolicy() {}

    public static void requireAcceptable(String password) {
        if (!isAcceptable(password)) {
            throw new BadRequestException(MESSAGE);
        }
    }

    public static boolean isAcceptable(String password) {
        if (password == null) {
            return false;
        }
        int length = password.length();
        if (length < MIN_LENGTH || length > MAX_LENGTH) {
            return false;
        }
        boolean letter = false;
        boolean digit = false;
        for (int i = 0; i < length; i++) {
            char c = password.charAt(i);
            if (Character.isLetter(c)) {
                letter = true;
            } else if (Character.isDigit(c)) {
                digit = true;
            }
        }
        if (!letter || !digit) {
            return false;
        }
        return !DENYLIST.contains(password.toLowerCase(Locale.ROOT));
    }
}
