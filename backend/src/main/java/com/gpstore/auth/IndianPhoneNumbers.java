package com.gpstore.auth;

import com.gpstore.exception.BadRequestException;

/**
 * India-only phone normalisation for OTP and account lookup.
 *
 * Canonical E.164-without-plus form is {@code 91} + 10-digit mobile
 * (e.g. {@code 919876543210}). Customer rows and OTP challenge rows keep the
 * 10-digit local form so existing unique indexes and Flutter clients still
 * match. MSG91 always receives the 91-prefixed form.
 */
public final class IndianPhoneNumbers {

    private IndianPhoneNumbers() {
    }

    public static String normalizeTo91(String raw) {
        String digits = digitsOnly(raw);

        if (digits.startsWith("00")) {
            digits = digits.substring(2);
        }
        if (digits.matches("^0[6-9]\\d{9}$")) {
            digits = digits.substring(1);
        }
        if (digits.matches("^[6-9]\\d{9}$")) {
            return "91" + digits;
        }
        if (digits.matches("^91[6-9]\\d{9}$")) {
            return digits;
        }
        throw new BadRequestException("Enter a valid Indian mobile number");
    }

    public static String toLocal10(String raw) {
        return normalizeTo91(raw).substring(2);
    }

    /**
     * Last four digits only, e.g. {@code ******3210}. Never log a full number.
     */
    public static String mask(String raw) {
        if (raw == null || raw.isBlank()) {
            return "******";
        }
        try {
            String local = toLocal10(raw);
            return "******" + local.substring(6);
        } catch (RuntimeException ignored) {
            return "******";
        }
    }

    private static String digitsOnly(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BadRequestException("Enter a valid Indian mobile number");
        }
        String trimmed = raw.strip();
        StringBuilder digits = new StringBuilder(trimmed.length());
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c >= '0' && c <= '9') {
                digits.append(c);
            } else if (c == '+' || c == '-' || c == ' ' || c == '(' || c == ')') {
                // allowed decoration
            } else {
                throw new BadRequestException("Enter a valid Indian mobile number");
            }
        }
        return digits.toString();
    }
}
