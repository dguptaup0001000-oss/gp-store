package com.gpstore.security;

import com.gpstore.entity.Role;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Which staff role may do what.
 *
 * <p>THE ONE RULE THIS FILE MUST NEVER BREAK: ADMIN KEEPS EVERYTHING. Every
 * account in the shop today is either CUSTOMER, DELIVERY_BOY, or ADMIN, and an
 * ADMIN can currently reach all forty-one staff-gated routes. Introducing
 * roles must not quietly take a capability away from a person who had it
 * yesterday, so ADMIN maps to the complete permission set and a test asserts
 * exactly that. The new roles are SUBSETS added beside it - nothing is
 * narrowed for anyone who already exists.
 *
 * <p>SUPER_ADMIN exists as a distinct name rather than a wider grant. Today it
 * equals ADMIN. It is here so the shop owner has a label that is theirs alone,
 * and so ADMIN can later be narrowed deliberately - as a decision, with a
 * migration to move the owner across first - rather than by someone editing
 * this map and discovering the consequences in production.
 *
 * <p>CUSTOMER and DELIVERY_BOY get NO admin permissions. A rider's access to
 * their own deliveries comes from ROLE_DELIVERY_BOY on the specific rules that
 * allow it, never from this map.
 */
public final class RolePermissions {

    private RolePermissions() {}

    private static final Set<AdminPermission> NONE =
            Collections.unmodifiableSet(EnumSet.noneOf(AdminPermission.class));

    /**
     * Everything a SHOP role can hold. Used by both SUPER_ADMIN and ADMIN.
     *
     * <p>WRITTEN AS A SUBTRACTION, NOT AS allOf(), and the difference is the
     * whole of the platform/shop split. allOf() means "ADMIN gets whatever
     * anybody adds to the enum next" - so the moment PLATFORM_ADMIN existed,
     * every shopkeeper in the marketplace would have silently become a
     * platform operator with a scope spanning every merchant. Nobody would
     * have edited a line to make that happen.
     *
     * <p>It still keeps the guarantee this file rests on: ADMIN loses nothing
     * it had. The permissions removed here are ones no shop role has ever
     * held, and StaffRolePermissionsTest pins the full set by name.
     */
    private static final Set<AdminPermission> EVERY_SHOP_PERMISSION =
            Collections.unmodifiableSet(EnumSet.complementOf(EnumSet.of(
                    // Runs the marketplace. See Role.PLATFORM_ADMIN.
                    AdminPermission.PLATFORM_ADMIN,
                    // Writes the SHARED catalogue. Under one shop
                    // CatalogDefinition hands this to whoever holds
                    // CATALOG_MANAGE, so the shopkeeper is unaffected today.
                    AdminPermission.CATALOG_DEFINE,
                    // The marketplace's own numbers. A shop owner reading
                    // http_server_requests across every tenant can estimate
                    // the whole platform's order volume, and every shop owner
                    // holds ADMIN. Excluded here is the ONLY thing keeping
                    // that out of a merchant's hands - EnumSet.complementOf
                    // would otherwise hand them each new permission by
                    // default, which is exactly the trap this set is written
                    // as a subtraction to avoid.
                    AdminPermission.PLATFORM_OBSERVABILITY)));

    /**
     * THE PLATFORM OWNER. Runs GP-STORE itself, not a shop in it.
     *
     * <p>SUPER_ADMIN used to be byte-identical to ADMIN, which was wrong in
     * both directions at once. It gave the shop owner the platform's
     * observability, and - less obviously and more damagingly - it withheld
     * PLATFORM_ADMIN from the platform owner, so {@link
     * com.gpstore.platform.TenantResolver} resolved SUPER_ADMIN to a single
     * shop like any shopkeeper. The person who owns the marketplace could not
     * see across it, and on an account with no shop membership at all the
     * resolver simply threw.
     *
     * <p>allOf is correct HERE and nowhere else: the platform owner is the one
     * role that should gain every permission anybody adds to the enum next.
     */
    private static final Set<AdminPermission> EVERY_PERMISSION =
            Collections.unmodifiableSet(EnumSet.allOf(AdminPermission.class));

    private static final Map<Role, Set<AdminPermission>> BY_ROLE = Map.of(
            Role.SUPER_ADMIN, EVERY_PERMISSION,

            // The shop owner. Everything inside their own shop, and nothing
            // that spans the marketplace. Unchanged from what ADMIN has always
            // held except that PLATFORM_OBSERVABILITY - which is new - is not
            // granted, so no existing capability is taken away.
            Role.ADMIN, EVERY_SHOP_PERMISSION,

            // Runs the marketplace: merchants, shop lifecycle, the shared
            // catalogue, and the reporting that spans shops.
            //
            // NARROWER THAN ADMIN INSIDE ANY ONE SHOP, on purpose. Reading an
            // order to settle a dispute is the platform's business; advancing
            // it, refunding it, or editing that shop's rider roster is the
            // merchant's. §103: the shops stay independent, and the platform
            // provides the technology.
            //
            // No SYSTEM_ADMIN either: /actuator, the API docs and bulk seeding
            // belong to whoever runs the deployment, which is a third job again.
            Role.PLATFORM_ADMIN, unmodifiable(
                    AdminPermission.PLATFORM_ADMIN,
                    // Runs the marketplace, so its numbers are their business.
                    AdminPermission.PLATFORM_OBSERVABILITY,
                    AdminPermission.CATALOG_DEFINE,
                    AdminPermission.CATALOG_VIEW,
                    AdminPermission.CATALOG_MANAGE,
                    AdminPermission.ORDERS_VIEW,
                    AdminPermission.PAYMENTS_VIEW,
                    AdminPermission.CUSTOMERS_VIEW,
                    AdminPermission.ANALYTICS_VIEW,
                    AdminPermission.AUDIT_VIEW),

            // Runs the shop day to day. Everything operational, including
            // refunds, but NOT the system surface: actuator, API docs and
            // bulk catalogue seeding stay with the owner.
            Role.MANAGER, unmodifiable(
                    AdminPermission.ORDERS_VIEW,
                    AdminPermission.ORDERS_MANAGE,
                    AdminPermission.PAYMENTS_VIEW,
                    AdminPermission.PAYMENTS_MANAGE,
                    AdminPermission.PAYMENTS_REFUND,
                    AdminPermission.CATALOG_VIEW,
                    AdminPermission.CATALOG_MANAGE,
                    AdminPermission.INVENTORY_MANAGE,
                    AdminPermission.COUPONS_MANAGE,
                    AdminPermission.CUSTOMERS_VIEW,
                    AdminPermission.CUSTOMERS_MANAGE,
                    AdminPermission.DELIVERY_VIEW,
                    AdminPermission.DELIVERY_MANAGE,
                    AdminPermission.REVIEWS_MODERATE,
                    AdminPermission.BROADCAST_SEND,
                    AdminPermission.ANALYTICS_VIEW,
                    AdminPermission.AUDIT_VIEW),

            // Stocks the shelves. Catalogue and stock, plus the sales figures
            // that tell them what to reorder. No orders, no money, no people.
            Role.INVENTORY_MANAGER, unmodifiable(
                    AdminPermission.CATALOG_VIEW,
                    AdminPermission.CATALOG_MANAGE,
                    AdminPermission.INVENTORY_MANAGE,
                    AdminPermission.ANALYTICS_VIEW),

            // Works the counter. Takes orders through to delivery and confirms
            // money in - but CANNOT refund, which is the one action on that
            // screen that moves money the other way.
            Role.ORDER_MANAGER, unmodifiable(
                    AdminPermission.ORDERS_VIEW,
                    AdminPermission.ORDERS_MANAGE,
                    AdminPermission.PAYMENTS_VIEW,
                    AdminPermission.PAYMENTS_MANAGE,
                    AdminPermission.CATALOG_VIEW,
                    AdminPermission.CUSTOMERS_VIEW,
                    AdminPermission.DELIVERY_VIEW),

            // Runs dispatch. The roster, the territory map and the pricing
            // rules, plus enough order visibility to know what is going out.
            Role.DELIVERY_MANAGER, unmodifiable(
                    AdminPermission.DELIVERY_VIEW,
                    AdminPermission.DELIVERY_MANAGE,
                    AdminPermission.ORDERS_VIEW,
                    AdminPermission.CUSTOMERS_VIEW),

            // Answers the phone. Can see enough to explain what happened to an
            // order, and can take down an abusive review. Changes nothing else.
            Role.SUPPORT, unmodifiable(
                    AdminPermission.ORDERS_VIEW,
                    AdminPermission.PAYMENTS_VIEW,
                    AdminPermission.CATALOG_VIEW,
                    AdminPermission.CUSTOMERS_VIEW,
                    AdminPermission.REVIEWS_MODERATE)
    );

    /**
     * Permissions for a role name as it appears in a JWT or a database row.
     *
     * <p>FAILS CLOSED. An unknown, null or blank role gets nothing rather than
     * a default grant: a token carrying a role this build has never heard of
     * is either from a newer deployment or forged, and neither should be
     * handed the shop.
     */
    public static Set<AdminPermission> forRoleName(String roleName) {
        if (roleName == null || roleName.isBlank()) {
            return NONE;
        }
        try {
            return forRole(Role.valueOf(roleName.trim().toUpperCase()));
        } catch (IllegalArgumentException unknownRole) {
            return NONE;
        }
    }

    public static Set<AdminPermission> forRole(Role role) {
        if (role == null) {
            return NONE;
        }
        return BY_ROLE.getOrDefault(role, NONE);
    }

    /**
     * Every authority a role carries, as a request should see them.
     *
     * ONE PLACE, because JwtFilter and the test annotation both need the
     * answer and a second copy is a drift waiting to happen.
     *
     * A RIDER IS ALSO A SHOPPER, and this is where that is decided. The same
     * person can hold a delivery job and buy their own groceries with the same
     * account - which is what a shop with three staff actually looks like -
     * and DELIVERY_BOY replacing CUSTOMER used to take their shopping away.
     * Not visibly, either: browsing and the cart are gated on being
     * authenticated, so everything looked normal right up to the checkout,
     * where the three payment routes require ROLE_CUSTOMER and the order they
     * had just built could not be paid for.
     *
     * So the delivery role ADDS to being a customer rather than replacing it.
     * It grants nothing new on its own: ROLE_CUSTOMER only reaches routes that
     * act on the caller's OWN cart, orders and payments, resolved from the
     * authenticated id and never from the request.
     *
     * Staff roles deliberately do not get it. A shopkeeper buying from their
     * own shop is a real thing but a separate decision, and it should be made
     * on purpose rather than inherited from a helper.
     */
    public static Set<String> authorityNames(Role role) {
        if (role == null) {
            return Set.of();
        }
        Set<String> authorities = new java.util.LinkedHashSet<>();
        authorities.add("ROLE_" + role.name());
        if (role == Role.DELIVERY_BOY) {
            authorities.add("ROLE_" + Role.CUSTOMER.name());
        }
        for (AdminPermission permission : forRole(role)) {
            authorities.add(permission.authority());
        }
        return Collections.unmodifiableSet(authorities);
    }

    /** Same, for a role name off a JWT or a database row. Fails closed. */
    public static Set<String> authorityNamesForRoleName(String roleName) {
        if (roleName == null || roleName.isBlank()) {
            return Set.of();
        }
        try {
            return authorityNames(Role.valueOf(roleName.trim().toUpperCase()));
        } catch (IllegalArgumentException unknownRole) {
            return Set.of();
        }
    }

    /** True for any role that is staff - i.e. holds at least one permission. */
    public static boolean isStaff(Role role) {
        return !forRole(role).isEmpty();
    }

    private static Set<AdminPermission> unmodifiable(AdminPermission... permissions) {
        return Collections.unmodifiableSet(EnumSet.copyOf(Set.of(permissions)));
    }
}
