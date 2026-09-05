package com.gpstore.platform;

/**
 * How many shops this deployment is running, and therefore how a request's
 * tenant is resolved.
 *
 * ONE CORE SYSTEM, NOT TWO PRODUCTS (§2). The alternative - a single-shop
 * branch and a marketplace branch - means every fix is written twice and the
 * two drift until the marketplace is a different product that has to be
 * re-tested from nothing. This flag is what makes one codebase serve both.
 *
 * It is a DEPLOYMENT setting, never a per-request one: a client cannot ask to
 * be treated as single-shop and thereby skip a tenant check.
 */
public enum PlatformMode {

    /**
     * Today. One shop, and every request resolves to it implicitly.
     *
     * This is what keeps the APKs already on people's phones working while
     * the marketplace is built: their tokens carry no shop claim, and under
     * this mode they do not need one.
     */
    SINGLE_SHOP,

    /**
     * Several shops, seeded rather than onboarded, for demonstration.
     *
     * Identical machinery to MULTI_SHOP_PRODUCTION - same tables, same
     * authorization, same isolation. The only difference is that the
     * merchants carry is_demo, so nothing presents them as real businesses
     * (§22, §23).
     */
    MULTI_SHOP_DEMO,

    /** Real merchants, real money, real customers. */
    MULTI_SHOP_PRODUCTION;

    /** Whether a request must name which shop it is acting on. */
    public boolean requiresExplicitShopContext() {
        return this != SINGLE_SHOP;
    }

    public boolean isMultiShop() {
        return this != SINGLE_SHOP;
    }
}
