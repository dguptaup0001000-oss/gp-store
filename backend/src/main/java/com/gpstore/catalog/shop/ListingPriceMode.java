package com.gpstore.catalog.shop;

/**
 * How honest a listing's price is allowed to be.
 *
 * <p>Online commerce can assume one number, because the customer pays it on
 * the spot and the shop has committed to it. Almost nothing bought in person
 * works that way. Gold moves daily. A sofa depends on the fabric. A repair
 * depends on what is actually wrong. A merchant forced to publish one exact
 * figure will either publish a wrong one or publish nothing, and both are
 * worse for the customer than being told the truth.
 *
 * <p>So a listing says what kind of number it is showing. The client renders
 * it accordingly, and nothing in the system reads a STARTING_FROM price as a
 * promise.
 *
 * <p>ONLINE LISTINGS ARE ALWAYS EXACT, and that is enforced rather than
 * assumed: a cart cannot total a range, so a shop cannot sell online at
 * "ask at shop". See ShopProductVariant.priceModeIsCoherentWithCommerceMode.
 */
public enum ListingPriceMode {

    /** One number, and the merchant stands behind it. The only mode online sales may use. */
    EXACT_PRICE,

    /** "Starting from X" - the floor is real, the final figure depends on the choice. */
    STARTING_FROM,

    /** "X to Y" - a band the merchant is confident the real price falls inside. */
    PRICE_RANGE,

    /** No figure at all. Honest for bullion, bespoke work, and diagnosis-first repairs. */
    ASK_AT_SHOP;

    /** Whether a customer-facing screen has any number to draw at all. */
    public boolean showsAFigure() {
        return this != ASK_AT_SHOP;
    }

    /** Whether the figure shown is the price, rather than a floor or a band. */
    public boolean isCommitted() {
        return this == EXACT_PRICE;
    }
}
