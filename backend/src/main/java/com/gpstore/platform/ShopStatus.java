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

    /** Shut for good. Records retained (§91), but there is no way back. */
    public boolean isTerminal() {
        return this == CLOSED;
    }

    /**
     * Which statuses this one may move to.
     *
     * PAUSED AND SUSPENDED ARE BOTH REVERSIBLE AND ARE NOT THE SAME THING.
     * PAUSED is the shopkeeper's own choice - a holiday, a renovation.
     * SUSPENDED is the platform stopping them. A shop must not be able to
     * clear its own suspension by pausing and unpausing, so SUSPENDED goes
     * only to ACTIVE or CLOSED, and the authorization for that move belongs to
     * the platform (see ShopLifecycleService).
     */
    public java.util.Set<ShopStatus> allowedNext() {
        return switch (this) {
            case DRAFT -> java.util.EnumSet.of(ACTIVE, CLOSED);
            case ACTIVE -> java.util.EnumSet.of(PAUSED, SUSPENDED, CLOSED);
            case PAUSED -> java.util.EnumSet.of(ACTIVE, SUSPENDED, CLOSED);
            case SUSPENDED -> java.util.EnumSet.of(ACTIVE, CLOSED);
            case CLOSED -> java.util.EnumSet.noneOf(ShopStatus.class);
        };
    }

    public boolean canMoveTo(ShopStatus next) {
        return next != null && allowedNext().contains(next);
    }

    /** Whether a shopkeeper may make this move themselves, without the platform. */
    public boolean isMerchantChoice(ShopStatus next) {
        // Pausing and reopening are the shop's own business. Suspending,
        // un-suspending and closing are not - a shop that could clear its own
        // suspension would make suspension meaningless.
        return (this == ACTIVE && next == PAUSED) || (this == PAUSED && next == ACTIVE);
    }
}
