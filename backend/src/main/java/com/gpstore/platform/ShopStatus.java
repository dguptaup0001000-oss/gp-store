package com.gpstore.platform;

/**
 * A single storefront's own status, which is NOT the merchant's.
 *
 * A merchant may run three shops and want one of them closed for a month
 * without touching the other two, and the platform may need to stop one shop
 * without suspending a business that is otherwise fine. Collapsing the two
 * would make both of those impossible.
 *
 * Distinct from the day-to-day open/closed switch the shopkeeper already has
 * (StoreOperationsSettings): that answers "are you taking orders right now",
 * this answers "does this storefront exist on the marketplace".
 */
public enum ShopStatus {

    /** Being set up. Not visible to customers. */
    DRAFT,

    /** Live on the marketplace. */
    ACTIVE,

    /** Live but not accepting orders - a holiday, a renovation. */
    PAUSED,

    /** Stopped by the platform. Reversible. */
    SUSPENDED,

    /** Shut for good. Records retained (§91). */
    CLOSED;

    public boolean isVisibleToCustomers() {
        return this == ACTIVE || this == PAUSED;
    }

    public boolean canAcceptOrders() {
        return this == ACTIVE;
    }
}
