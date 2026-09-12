package com.gpstore.rating;

/**
 * WHY the customer rated the ITEM that way (§18).
 *
 * <p>Separate from {@link ShopRatingReason} because §17 separates the two
 * questions. This one travels with the product across every shop that sells
 * it: "not fresh" said of a packet of paneer is about the paneer, and the
 * next shop to stock it inherits that review the same way it inherits the
 * photo and the pack size.
 */
public enum ProductReviewReason {

    GOOD_QUALITY(true),
    POOR_QUALITY(false),
    FRESH(true),
    NOT_FRESH(false),
    GOOD_VALUE(true),
    OVERPRICED(false),
    AS_DESCRIBED(true),
    NOT_AS_DESCRIBED(false),
    DAMAGED(false),
    NEAR_EXPIRY(false);

    private final boolean praise;

    ProductReviewReason(boolean praise) {
        this.praise = praise;
    }

    public boolean isPraise() {
        return praise;
    }
}
