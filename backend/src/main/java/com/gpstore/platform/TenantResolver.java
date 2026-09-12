package com.gpstore.platform;

import com.gpstore.security.AdminPermission;
import com.gpstore.security.CurrentUser;

import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Decides whose data a request may touch, from the credential and nothing else.
 *
 * THE RULE THIS EXISTS TO ENFORCE (§78): the tenant is never taken from the
 * request. Not a path segment, not a query parameter, not a header, not a
 * body field. A shop id supplied by a caller is at most a SELECTION among
 * shops the credential already permits - it can narrow, it can never grant.
 *
 * That rule is not new to this codebase; it is the one the customer paths
 * already follow. CartController reads CurrentUser.customerId() from the
 * token and ignores any customer id in the body, which is why a customer
 * cannot empty somebody else's basket by editing a request. Shop scoping is
 * the same rule applied one level up.
 *
 * WHAT IT DOES NOT DO YET. Slice 1 resolves the scope; it does not enforce
 * it. Nothing filters queries on the strength of this answer so far, so no
 * behaviour changes. Enforcement is the next slice, and it is deliberately
 * separate: resolution wrong is a bug you can see in a test, enforcement
 * wrong is a bug you see in somebody else's order list.
 */
@Component
public class TenantResolver {

    private final PlatformProperties platform;
    private final CurrentUser currentUser;
    private final ShopRepository shops;
    private final ShopMembership membership;
    private final ShopDiscovery discovery;
    private final CustomerShopPreference customerShops;
    private final com.gpstore.repository.DeliveryPartnerRepository riders;

    public TenantResolver(PlatformProperties platform, CurrentUser currentUser,
                          ShopRepository shops, ShopMembership membership,
                          ShopDiscovery discovery, CustomerShopPreference customerShops,
                          com.gpstore.repository.DeliveryPartnerRepository riders) {
        this.platform = platform;
        this.currentUser = currentUser;
        this.shops = shops;
        this.membership = membership;
        this.discovery = discovery;
        this.customerShops = customerShops;
        this.riders = riders;
    }

    /**
     * The scope for the request on this thread.
     *
     * SINGLE_SHOP RESOLVES IMPLICITLY, and that is what keeps every APK
     * already on a customer's phone working. Those tokens carry no shop
     * claim and never will; under one shop they do not need one, because
     * there is exactly one answer.
     */
    public TenantScope resolve() {
        if (!platform.getMode().requiresExplicitShopContext()) {
            return TenantScope.ofShop(firstShopId());
        }

        // A platform administrator legitimately spans shops.
        //
        // PLATFORM_ADMIN, NOT SYSTEM_ADMIN, and the difference is not
        // cosmetic. SYSTEM_ADMIN means "the dangerous surface" - actuator,
        // API docs, bulk seeding - and EVERY existing shop owner holds it,
        // because ADMIN was granted the whole permission set. Reading it here
        // would have resolved every shopkeeper in the marketplace to a scope
        // spanning every other merchant, on the first day a second shop
        // existed, with nothing in any log to show for it.
        if (currentUser.has(AdminPermission.PLATFORM_ADMIN)) {
            return TenantScope.platform();
        }

        // A RIDER'S SHOP IS ON THEIR ROSTER ROW.
        //
        // A worker session carries a workerId and no customer id at all -
        // their credentials live on delivery_partners, not on customers - so
        // the staff list has nothing to say about them. The roster row already
        // carries the shop they were hired by (V46), and that is the answer.
        //
        // Read live, like everything else here: a rider moved between shops,
        // or taken off the roster, stops working there on the next request
        // rather than when their shift token expires.
        Long workerId = currentWorkerIdOrNull();
        if (workerId != null) {
            Long riderShop = riders.findById(workerId)
                    .map(com.gpstore.entity.DeliveryPartner::getShopId)
                    .orElse(null);
            if (riderShop != null) {
                return TenantScope.ofShop(riderShop);
            }
            throw new IllegalStateException(
                    "This rider is not on any shop's roster, so there is no shop to work in.");
        }

        // Staff work in the shops they are on the staff list of. Read from
        // the database on every request rather than from a claim inside the
        // token - see ShopMembership for why that is the same decision this
        // codebase already made for permissions.
        Long customerId = currentUserIdOrNull();
        if (customerId != null) {
            java.util.Optional<Long> home = membership.defaultShopIdFor(customerId);
            if (home.isPresent()) {
                // A shop that has been closed, or whose merchant has been
                // removed, has nothing left to administer. A SUSPENDED one
                // does: its staff can still sign in and read why, which is
                // deliberate - see ShopMembership.isOperable.
                if (!membership.isOperable(home.get())) {
                    throw new IllegalStateException(
                            "This shop is closed, or its merchant is no longer on the platform.");
                }
                return TenantScope.ofShop(home.get());
            }
            if (!membership.shopIdsFor(customerId).isEmpty()) {
                throw new IllegalStateException(
                        "This account works in more than one shop and none is marked as its "
                                + "default, so there is no single answer to which shop this "
                                + "request is for. Choosing one for them would be choosing one "
                                + "merchant's data over another's.");
            }
        }

        // A CUSTOMER IS NOT STAFF OF ANYTHING, and must not have to be.
        //
        // Requiring a staff membership to resolve a scope was right while the
        // only people with a scope were shopkeepers; applied to customers it
        // would mean nobody can shop on the marketplace at all. A customer's
        // shop is the nearest one that will deliver to their address - the
        // shop's own radius, nearest first (ShopDiscovery) - or the storefront
        // they explicitly opened, which arrives through select() and is
        // checked there like every other selection.
        //
        // THIS IS NOT AN AUTHORIZATION. Browsing a storefront is something any
        // customer may do; the scope it produces lets them read that shop's
        // prices and stock, which is what a shopfront is. It does not let them
        // act as the shop - that is ShopMembership, and it is a different
        // question.
        Optional<Long> browsing = customerShops.shopForCurrentCustomer();
        if (browsing.isPresent()) {
            return TenantScope.ofShop(browsing.get());
        }

        throw new IllegalStateException(
                "Multi-shop mode is on and this request names no shop. A staff account with no "
                        + "membership, or a customer with no serviceable address, cannot be "
                        + "resolved to one - and picking a shop for them would be inventing an "
                        + "authorization nobody granted.");
    }

    /**
     * The shop a caller SELECTED, verified against the shops they may work in.
     *
     * NOT A SECOND WAY TO RESOLVE. §78 draws the line exactly here: a shop id
     * from a request can NARROW to something the credential already permits,
     * and can never grant. This method is the narrowing, kept separate from
     * {@link #resolve} and named for what it does, so that the resolver
     * remains a function of the credential alone and there is no overload
     * anybody could mistake for one.
     *
     * A shop the caller is not staff of is refused - not silently ignored,
     * because a merchant whose shop switcher quietly showed them the wrong
     * shop's orders would be worse than an error.
     *
     * @throws IllegalStateException when the caller may not work in that shop
     */
    public TenantScope select(Long requestedShopId) {
        if (requestedShopId == null) {
            return resolve();
        }

        // A platform administrator may look into any shop, and says which.
        if (currentUser.has(AdminPermission.PLATFORM_ADMIN)) {
            return TenantScope.ofShop(requestedShopId);
        }

        Long customerId = currentUserIdOrNull();
        if (customerId != null && membership.permits(customerId, requestedShopId)) {
            return TenantScope.ofShop(requestedShopId);
        }

        // A CUSTOMER OPENING A STOREFRONT. Any shop the marketplace shows to
        // customers may be browsed by any of them, so selecting one is not an
        // escalation - it is choosing which shop's window to stand in front
        // of. A shop that is suspended, closed or still a draft is refused,
        // because it is not on the marketplace to be looked at.
        //
        // ONLY FOR SOMEBODY WHO IS NOT STAFF ANYWHERE, and that condition is
        // the whole security of this branch. Without it a shopkeeper - who is
        // also, technically, a person who could browse - would be able to
        // name any shop on the marketplace and be given a scope inside it,
        // which is precisely the cross-merchant move Slice 3 exists to
        // prevent. A staff account is restricted to the shops it is staff of,
        // full stop; if a shopkeeper wants to shop elsewhere, that is a
        // customer account.
        boolean isStaffSomewhere =
                customerId != null && !membership.shopIdsFor(customerId).isEmpty();
        if (!isStaffSomewhere && discovery.isBrowsableByCustomers(requestedShopId)) {
            return TenantScope.ofShop(requestedShopId);
        }

        // Naming the shop the caller would have got anyway is a no-op, not an
        // escalation - which matters under one shop, where a customer has no
        // staff membership and there is exactly one shop they could possibly
        // mean.
        TenantScope fromCredential = resolve();
        if (fromCredential.isSingleShop() && requestedShopId.equals(fromCredential.shopId())) {
            return fromCredential;
        }

        throw new IllegalStateException(
                "This account is not on the staff of the shop it asked to act for.");
    }

    /** The signed-in rider, or null when this is not a worker session. */
    private Long currentWorkerIdOrNull() {
        try {
            return currentUser.get().getWorkerId();
        } catch (RuntimeException noCredential) {
            return null;
        }
    }

    /** The signed-in account, or null when there is no credential at all. */
    private Long currentUserIdOrNull() {
        try {
            return currentUser.customerId();
        } catch (RuntimeException noCredential) {
            return null;
        }
    }

    /**
     * Shop #1's id, looked up by its code rather than assumed to be 1.
     *
     * The constant exists (Shop.FIRST_SHOP_ID) and is right today, but a
     * database restored from a dump, or one where the row was recreated, can
     * carry a different id for the same shop. The code is the stable name;
     * the id is an implementation detail of one database.
     */
    private Long firstShopId() {
        return shops.findByCode(platform.getFirstShopCode())
                .map(Shop::getId)
                .orElseThrow(() -> new IllegalStateException(
                        "Shop #1 ('" + platform.getFirstShopCode() + "') is missing. Single-shop "
                                + "mode has nothing to resolve to - ShopBootstrap should have "
                                + "created it at startup."));
    }
}
