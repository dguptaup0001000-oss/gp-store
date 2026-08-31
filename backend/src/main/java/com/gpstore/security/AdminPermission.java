package com.gpstore.security;

/**
 * What a staff member is allowed to do, independent of what they are called.
 *
 * <p>WHY PERMISSIONS AND NOT A LIST OF ROLES PER RULE. SecurityConfig has
 * forty-one staff-gated rules. Writing {@code hasAnyRole("ADMIN", "MANAGER",
 * "ORDER_MANAGER", ...)} on each one means every new role is a forty-one line
 * edit, and it makes each rule state WHO rather than WHY - so the next person
 * reading it cannot tell whether a role was left out deliberately or by
 * mistake. A rule that says {@code PERM_PAYMENTS_REFUND} explains itself, and
 * adding a role becomes one line in {@link RolePermissions}.
 *
 * <p>THE AUTHORITY STRING IS PART OF THE CONTRACT. JwtFilter grants these as
 * {@code PERM_<name>} alongside the usual {@code ROLE_<name>}, and
 * SecurityConfig matches on that exact string. Renaming a constant here
 * silently unguards whatever rule referenced it, so the names are pinned by a
 * test.
 */
public enum AdminPermission {

    /** See orders, order items, and invoices across every customer. */
    ORDERS_VIEW,

    /** Advance or cancel an order's status. */
    ORDERS_MANAGE,

    /** See payments across every customer. */
    PAYMENTS_VIEW,

    /**
     * Confirm money in: mark a UPI transfer received, or a COD amount
     * collected. Separate from a refund because taking money and giving it
     * back are not the same trust.
     */
    PAYMENTS_MANAGE,

    /**
     * Send money back to a customer. THE NARROWEST GRANT ON THIS LIST - a
     * refund cannot be undone from the app, so it belongs to whoever answers
     * for the shop's bank balance and to nobody else.
     */
    PAYMENTS_REFUND,

    /** See the full catalogue, including withdrawn and inactive products. */
    CATALOG_VIEW,

    /** Create, edit, or withdraw products, categories, variants and images. */
    CATALOG_MANAGE,

    /** Restock, correct stock counts, and set reorder points. */
    INVENTORY_MANAGE,

    /** Create and withdraw discount offers. */
    COUPONS_MANAGE,

    /** See customer accounts and their carts, wishlists and order history. */
    CUSTOMERS_VIEW,

    /** Create a customer account, or deactivate one. */
    CUSTOMERS_MANAGE,

    /** See the delivery roster, assignments, and guarantee breaches. */
    DELIVERY_VIEW,

    /**
     * Edit the roster, the territory map, and delivery pricing. Redrawing a
     * boundary silently reroutes every future order in that area, and the
     * pricing numbers decide what every customer pays.
     */
    DELIVERY_MANAGE,

    /** Remove a customer's review. */
    REVIEWS_MODERATE,

    /** Push a notification to every customer at once. */
    BROADCAST_SEND,

    /** See sales figures, top products, and the order breakdown. */
    ANALYTICS_VIEW,

    /** Read the record of what staff have done. */
    AUDIT_VIEW,

    /**
     * The dangerous surface: actuator, API docs, bulk catalogue seeding, and
     * anything new that lands under /api/admin/** before it has been given a
     * narrower permission of its own. Deliberately NOT granted to the
     * operational roles - a route nobody has classified yet should be
     * reachable by the shop owner, not by whoever happens to be on shift.
     */
    SYSTEM_ADMIN;

    /** The authority string SecurityConfig matches on. */
    public String authority() {
        return "PERM_" + name();
    }
}
