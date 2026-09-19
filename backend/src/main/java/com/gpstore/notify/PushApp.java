package com.gpstore.notify;

/**
 * Which of the four apps an install is.
 *
 * <p>WHY A TOKEN MUST SAY THIS. All four apps sign in through the same
 * endpoints, so before this existed a token was just "a device belonging to
 * account 993" - and a new order sent to every device of every ADMIN reached
 * the customer app on the owner's own phone as readily as the counter tablet.
 * Recording the app is what lets a merchant alert go to MERCHANT_ADMIN installs
 * and nowhere else.
 */
public enum PushApp {
    CUSTOMER,
    MERCHANT_ADMIN,
    SUPER_ADMIN,
    WORKER;

    /** Never throws: an unrecognised or missing app is treated as the customer app. */
    public static PushApp parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return CUSTOMER;
        }
        try {
            return PushApp.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            return CUSTOMER;
        }
    }
}
