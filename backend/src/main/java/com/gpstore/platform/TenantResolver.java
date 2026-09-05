package com.gpstore.platform;

import com.gpstore.security.AdminPermission;
import com.gpstore.security.CurrentUser;
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

    public TenantResolver(PlatformProperties platform, CurrentUser currentUser, ShopRepository shops) {
        this.platform = platform;
        this.currentUser = currentUser;
        this.shops = shops;
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

        // A platform administrator legitimately spans shops. Everyone else,
        // in a marketplace deployment, must carry a shop claim - and until
        // staff tokens carry one (next slice), the honest answer is to
        // refuse rather than to guess a shop for them.
        if (currentUser.has(AdminPermission.SYSTEM_ADMIN)) {
            return TenantScope.platform();
        }

        throw new IllegalStateException(
                "Multi-shop mode is on but this credential names no shop. A token issued before "
                        + "shop claims existed cannot be resolved to a shop, and picking one for "
                        + "it would be inventing an authorization nobody granted.");
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
