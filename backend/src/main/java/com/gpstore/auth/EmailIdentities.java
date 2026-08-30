package com.gpstore.auth;

import com.gpstore.exception.BadRequestException;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Email identity for OTP. Never log the raw address without {@link #mask}.
 */
public final class EmailIdentities {

    private static final Pattern EMAIL = Pattern.compile(
            "^[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}$",
            Pattern.CASE_INSENSITIVE);

    private EmailIdentities() {}

    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BadRequestException("Enter a valid email address");
        }
        String email = raw.trim().toLowerCase(Locale.ROOT);
        if (email.length() > 320 || !EMAIL.matcher(email).matches()) {
            throw new BadRequestException("Enter a valid email address");
        }
        return email;
    }

    public static String mask(String email) {
        if (email == null || email.isBlank()) {
            return "****";
        }
        String value = email.trim();
        int at = value.indexOf('@');
        if (at <= 0) {
            return "****";
        }
        String local = value.substring(0, at);
        String domain = value.substring(at);
        char first = local.charAt(0);
        return first + "***" + domain;
    }
}
