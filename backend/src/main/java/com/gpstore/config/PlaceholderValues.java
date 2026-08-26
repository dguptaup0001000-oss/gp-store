package com.gpstore.config;

/**
 * Values that must never be shown to a customer or accepted as production
 * configuration. Matched by content, not by profile, so a missing VPS env
 * var cannot hide behind a Spring default.
 */
public final class PlaceholderValues {

    private PlaceholderValues() {}

    /**
     * Secrets (passwords, JWT). Does not treat "todo" or "example.com" as
     * matches so a high-entropy value cannot fail for containing those
     * letters as a substring.
     */
    public static boolean isSecretPlaceholder(String value) {
        if (value == null || value.isBlank()) {
            return true;
        }
        String v = value.trim().toLowerCase();
        if (v.contains("change_me") || v.contains("changeme") || v.contains("change-me")) {
            return true;
        }
        if (v.contains("xxxxxx")) {
            return true;
        }
        if (v.contains("placeholder")) {
            return true;
        }
        if (v.contains("yourstorename") || v.contains("your-store")) {
            return true;
        }
        return false;
    }

    public static boolean isBlankOrPlaceholder(String value) {
        if (isSecretPlaceholder(value)) {
            return true;
        }
        String v = value.trim().toLowerCase();
        if (v.contains("example.com")) {
            return true;
        }
        if (v.contains("todo")) {
            return true;
        }
        return false;
    }

    /** Empty string when the value is not fit to show a customer. */
    public static String publicOrEmpty(String value) {
        return isBlankOrPlaceholder(value) ? "" : value.trim();
    }
}
