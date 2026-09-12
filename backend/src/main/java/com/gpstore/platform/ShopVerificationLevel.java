package com.gpstore.platform;

/**
 * How far GP-STORE has checked who this shop is.
 *
 * <p>THREE LEVELS, AND THE THIRD ONE IS NOT HERE. §10 names four states, and
 * only these three are things the platform GRANTS after looking at documents.
 * The fourth - TRUSTED - is earned from how the shop actually trades, is
 * computed from its own record ({@link ShopReliability}), and deliberately
 * has no representation anywhere that anyone could write to. A badge that
 * exists as a value is a badge that can be set, and a badge that can be set is
 * one that can be sold.
 *
 * <p>NONE IS THE HONEST DEFAULT and every shop starts there, including Shop
 * #1. Backfilling a badge onto shops nobody has checked would be the platform
 * vouching for them, which is the one thing a verification badge must never
 * do falsely - it is worth less than nothing the moment a customer finds a
 * verified shop that was never looked at.
 */
public enum ShopVerificationLevel {

    /** Nobody has checked. The starting state, and not a mark against the shop. */
    NONE,

    /**
     * Identity and contact confirmed: a real person, a reachable phone, a
     * shop that is where it says it is.
     */
    VERIFIED,

    /**
     * The business itself confirmed: registration, GSTIN, food licence where
     * the shop needs one. Everything VERIFIED means, and the paperwork too.
     */
    BUSINESS_VERIFIED;

    /** Whether this level includes everything {@code other} means. */
    public boolean isAtLeast(ShopVerificationLevel other) {
        return other != null && ordinal() >= other.ordinal();
    }

    /** What a customer is told. Null for NONE: no badge, rather than a bad one. */
    public String badge() {
        return switch (this) {
            case NONE -> null;
            case VERIFIED -> "Verified shop";
            case BUSINESS_VERIFIED -> "Business verified";
        };
    }
}
