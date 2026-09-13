package com.gpstore.platform;

/**
 * M-000001 and S-000001: what a person says out loud.
 *
 * FOR READING, NEVER FOR DECIDING. §3 is explicit that these must not be
 * relied on for authorization, and the surest way to honour that is for them
 * not to exist anywhere a decision is made. Nothing parses them, no route
 * accepts one, and no query looks one up - they are computed at the edge, on
 * the way out, from the row's own primary key. An identifier that is never
 * read back cannot be forged into a permission.
 *
 * DERIVED RATHER THAN STORED, and that is a deliberate choice against the
 * obvious alternative of a column. A stored code needs a generator, a
 * uniqueness constraint, a backfill for every row that already exists, and a
 * migration that can fail halfway. Derived, it is correct for Shop #1 and for
 * the ten-thousandth shop on the day each is created, with nothing to keep in
 * step and nothing to go wrong.
 *
 * NOT THE SHOP CODE. shops.code is a different thing and stays exactly as it
 * is (§43): the merchant picks it, customers can see it, and Shop #1 has
 * carried SHOP-1 since the first day. This sits beside it, so "which shop is
 * S-000002" has an answer that does not depend on what anybody typed.
 *
 * SIX DIGITS, AND THEN MORE. Padding stops at the width, it does not truncate
 * to it - the millionth merchant is M-1000000 rather than a collision with
 * M-000000.
 */
public final class PublicIds {

    private static final int WIDTH = 6;

    private PublicIds() {
    }

    /** The merchant reference, or null when there is no merchant. */
    public static String merchant(Long merchantId) {
        return merchantId == null ? null : "M-" + pad(merchantId);
    }

    /** The shop reference, or null when there is no shop. */
    public static String shop(Long shopId) {
        return shopId == null ? null : "S-" + pad(shopId);
    }

    private static String pad(long id) {
        String digits = Long.toString(Math.abs(id));
        return digits.length() >= WIDTH ? digits : "0".repeat(WIDTH - digits.length()) + digits;
    }
}
