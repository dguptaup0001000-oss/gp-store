package com.gpstore.entity;

/**
 * Who someone is to the shop.
 *
 * <p>STORED AS A STRING ({@code @Enumerated(EnumType.STRING)} on
 * Customer.role), so the ORDER OF THESE CONSTANTS CARRIES NO MEANING and
 * adding values cannot reshuffle existing rows. Were this ordinal, inserting
 * anything above DELIVERY_BOY would silently turn every rider into an admin.
 *
 * <p>THE DATABASE ALSO CONSTRAINS THIS COLUMN. Hibernate generated a CHECK
 * constraint listing the three original values, and {@code ddl-auto=validate}
 * does not inspect check constraints - so adding a value here without
 * V32__staff_roles.sql passes startup and then rejects every insert of a new
 * role at runtime. The enum and that migration are a matched pair.
 */
public enum Role {

    CUSTOMER,

    /**
     * Full access, unchanged. Every existing staff account is an ADMIN and
     * keeps exactly what it had - see RolePermissions for why that guarantee
     * matters more than a tidy hierarchy.
     */
    ADMIN,

    DELIVERY_BOY,

    // ------------------------------------------------------------------
    // Staff roles. All are subsets of ADMIN; none takes anything away from
    // an account that exists today. See RolePermissions for what each may do.
    // ------------------------------------------------------------------

    /** The shop owner. Today identical to ADMIN; a name that is theirs alone. */
    SUPER_ADMIN,

    /** Runs the shop day to day. Everything operational, not the system surface. */
    MANAGER,

    /** Stocks the shelves: catalogue, stock, and the figures to reorder by. */
    INVENTORY_MANAGER,

    /** Works the counter: orders through to delivery, money in but never out. */
    ORDER_MANAGER,

    /** Runs dispatch: the roster, the territory map, delivery pricing. */
    DELIVERY_MANAGER,

    /** Answers the phone: sees enough to explain an order, moderates reviews. */
    SUPPORT,

    /**
     * Runs the MARKETPLACE, not a shop.
     *
     * <p>DELIBERATELY NOT A WIDER ADMIN. The obvious shortcut - let ADMIN mean
     * "can do anything, including across shops" - is what turns every
     * shopkeeper into a platform operator the day a second merchant signs up,
     * because every existing staff account is an ADMIN. This role is the other
     * axis: it governs merchants, shop lifecycle and the shared catalogue, and
     * it is the only role whose scope spans shops.
     *
     * <p>It is also NARROWER than ADMIN inside any one shop. A platform
     * administrator can read an order to settle a dispute; they cannot advance
     * it, refund it, or edit that shop's rider roster. Running the market is
     * not the same job as running a shop, and §103 is explicit that the shops
     * stay independent.
     */
    PLATFORM_ADMIN

}
