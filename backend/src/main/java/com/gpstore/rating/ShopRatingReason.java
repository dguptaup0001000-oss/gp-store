package com.gpstore.rating;

/**
 * WHY the customer gave the shop that many stars (§18).
 *
 * <p>THREE STARS TELLS A SHOPKEEPER NOTHING. Three stars and "delivered late"
 * tells them to look at their dispatch times; three stars and "items missing"
 * tells them to look at their packing. The reason is the half of a rating a
 * shop can actually act on, which is why §18 asks for it and why it is a
 * closed list rather than free text - free text cannot be counted, and
 * "what do most of my three-star ratings say" is the question worth asking.
 *
 * <p>POSITIVE REASONS EXIST TOO, deliberately. A list of nothing but
 * complaints turns the reason picker into an accusation form, and a customer
 * who wants to say the packing was excellent should not have to write an
 * essay to do it.
 *
 * <p>ABOUT THE SHOP, NOT THE ITEM. Nothing here is about whether the biscuit
 * was nice - that is {@link ProductReviewReason}, on a different row, for the
 * reason §17 gives: a shop that delivered a perfect packet of a mediocre
 * biscuit should not be marked down for the biscuit.
 */
public enum ShopRatingReason {

    DELIVERED_ON_TIME(true),
    DELIVERED_LATE(false),
    PACKED_WELL(true),
    POOR_PACKAGING(false),
    ITEMS_MISSING(false),
    WRONG_ITEMS(false),
    FRIENDLY_SERVICE(true),
    RUDE_SERVICE(false),
    FAIR_PRICES(true),
    HARD_TO_CONTACT(false),
    ORDER_CANCELLED_BY_SHOP(false);

    private final boolean praise;

    ShopRatingReason(boolean praise) {
        this.praise = praise;
    }

    /** Whether this is something the customer liked. */
    public boolean isPraise() {
        return praise;
    }
}
