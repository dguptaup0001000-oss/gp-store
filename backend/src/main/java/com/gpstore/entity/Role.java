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

    /**
     * THE PLATFORM OWNER - runs GP-STORE itself, and takes every decision
     * about it. The single highest authority in the app: every permission the
     * enum defines, including the ones whose scope spans shops. It is NOT a
     * wider ADMIN with a nicer name; ADMIN is the shop owner, and the two are
     * different jobs at different levels. See RolePermissions.
     */
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
     * A LEGACY ALIAS FOR {@link #SUPER_ADMIN}. NOT A SEPARATE BUSINESS ROLE.
     *
     * <p>GP-STORE HAS FOUR BUSINESS ROLES: CUSTOMER shops, DELIVERY_BOY
     * delivers, ADMIN owns a shop, SUPER_ADMIN owns the platform. This
     * constant is none of them.
     *
     * <p>It used to be a third authority level - wider than ADMIN across
     * shops, deliberately narrower inside any one of them - on the theory that
     * running the market and running a shop were different jobs that should
     * not be held by one account. The owner of GP-STORE has settled it the
     * other way: SUPER_ADMIN is the single highest authority and decides
     * everything, so there is nothing left for a second platform role to be.
     *
     * <p>WHY IT IS STILL HERE. customers.role is a string under a CHECK
     * constraint that V50__staff_shop_identity.sql taught to accept
     * 'PLATFORM_ADMIN'. Deleting the constant would make Role.valueOf throw on
     * any row still holding it - an outage for that account, in a database
     * this code cannot inspect from here. No migration ever seeded such an
     * account, so this is a precaution rather than a known case, and it costs
     * nothing: RolePermissions grants it the SAME SET as SUPER_ADMIN by
     * reference, so the two cannot drift, and TheGpStoreRoleModelTest asserts
     * that equality rather than trusting this sentence.
     *
     * <p>DO NOT USE IT FOR NEW ACCOUNTS. Give the platform owner SUPER_ADMIN.
     */
    PLATFORM_ADMIN

}
