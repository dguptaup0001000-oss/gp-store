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
     * The digits worth matching a phone number against, for a SEARCH box.
     *
     * <p>WHY {@link #normalizeTo91} CANNOT BE USED HERE. It validates, and it
     * throws on anything that is not a complete Indian mobile. That is right
     * for signing in and wrong for searching: an operator typing a number one
     * key at a time is holding a partial number for most of the interaction,
     * and turning each of those keystrokes into a 400 would make the search
     * box unusable.
     *
     * <p>SO THIS NORMALISES WITHOUT JUDGING. It strips spaces, dashes,
     * brackets and a leading +, drops a 91 or 0 country/trunk prefix when what
     * follows still looks like a mobile, and hands back the digits. The caller
     * matches those as a substring, so "+91 98765-43210", "098765 43210" and
     * "9876543210" all find the same person - which is the entire point, since
     * an operator is usually reading the number off something a customer wrote
     * by hand.
     *
     * @return the digits to match, or null when the input is not numeric
     *         enough to be a phone number at all - in which case the caller
     *         should treat the term as a name or an email instead.
     */
    public static String searchDigits(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        StringBuilder digits = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c >= '0' && c <= '9') {
                digits.append(c);
            }
        }
        String value = digits.toString();
        // Fewer than four digits matches most of the table and is not a phone
        // search by any reasonable reading - "99" should not return everyone.
        if (value.length() < 4) {
            return null;
        }
        if (value.startsWith("00")) {
            value = value.substring(2);
        }
        // Only strip a prefix when what remains still looks like a mobile, so
        // a number that merely happens to begin with 91 is left intact.
        if (value.length() > 10 && value.startsWith("91")) {
            value = value.substring(2);
        } else if (value.length() > 10 && value.startsWith("0")) {
            value = value.substring(1);
        }
        // Longer than a mobile means the trailing ten digits are the number.
        if (value.length() > 10) {
            value = value.substring(value.length() - 10);
        }
        return value;
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
