package com.gpstore.catalog.shop;

/**
 * How a customer is meant to obtain what a shop has listed.
 *
 * <h2>Why this is one column and not three subsystems</h2>
 *
 * <p>GP-STORE sells groceries online, and it also wants to be where somebody
 * finds the jeweller who has the ring, or the barber who does the haircut.
 * Those are not three applications. They are the same marketplace answering
 * the same question - what can I get near me - with three different endings.
 *
 * <p>So the mode lives on the LISTING, which is the row that already says
 * "this shop offers this thing at this price". It is deliberately NOT on the
 * shop and NOT on the central product:
 *
 * <ul>
 *   <li>NOT ON THE SHOP, because one phone shop sensibly sells a charger
 *       online, asks you to come and look at a handset, and repairs a screen
 *       on the bench. Classifying the merchant would force them to pick one
 *       and lie about the other two.</li>
 *   <li>NOT ON THE CENTRAL PRODUCT, because the same catalogue row can be
 *       both: a big retailer may ship the handset while the shop down the
 *       road wants you to come in and hold it. The mode is a fact about an
 *       offer, not about a thing.</li>
 * </ul>
 *
 * <h2>Nothing here is trade-specific, on purpose</h2>
 *
 * <p>There is no JewelleryListing, no BarberService, no CarWashBooking. The
 * marketplace already carries a hundred business types through one generic
 * product/variant/attribute model, and that generality is what let it grow
 * past kirana. A jeweller's purity and a mechanic's vehicle class are both
 * just variant attributes; what differs between them is only how the sale
 * ends, which is this enum and nothing more.
 */
public enum CommerceMode {

    /**
     * The existing marketplace: add to cart, pay, and it is delivered or
     * collected. Everything that existed before this enum is this, and the
     * migration that introduced the column said so for every row.
     */
    ONLINE_PURCHASE,

    /**
     * Discoverable online, bought in person.
     *
     * <p>For goods people want in their hands before they pay - a ring, a
     * sofa, a handset, a saree. The listing carries images, specifications
     * and a price the merchant is willing to publish, and the customer journey
     * ends at the shop door rather than at a checkout.
     *
     * <p>THE BACKEND REFUSES TO SELL THESE, and that refusal is not a UI
     * decision. See ShopCatalog.refuseIfNotBuyableOnline.
     */
    VISIT_TO_BUY,

    /**
     * Work performed at the business: a haircut, a wash, a repair.
     *
     * <p>Priced and described like anything else, but there is no stock to
     * decrement and nothing to deliver. Appointments are deliberately absent
     * for now rather than half-built; when they arrive they attach to this
     * mode without anything else moving.
     */
    SERVICE_AT_SHOP;

    /**
     * Whether a cart and a payment are the right ending for this.
     *
     * <p>Asked in exactly one place so that "can this be bought online" cannot
     * come to two different answers in two different files.
     */
    public boolean isBuyableOnline() {
        return this == ONLINE_PURCHASE;
    }

    /** The label a customer should see on a card, so clients need not invent one. */
    public String customerLabel() {
        return switch (this) {
            case ONLINE_PURCHASE -> "Buy Online";
            case VISIT_TO_BUY -> "Visit to Buy";
            case SERVICE_AT_SHOP -> "Service at Shop";
        };
    }
}
