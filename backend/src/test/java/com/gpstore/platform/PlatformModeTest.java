package com.gpstore.platform;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Which mode a deployment runs in, and what happens when nobody says.
 *
 * <p>THE RULE THIS FILE USED TO PIN, AND WHY IT WENT. It read: "FAILING CLOSED
 * IS THE WHOLE POINT. A typo in an environment variable must not be able to put
 * a live deployment into a marketplace mode it was never configured for - so
 * anything unrecognised, blank or absent lands on SINGLE_SHOP, the mode that
 * changes nothing." Two tests asserted it: {@code defaultsToSingleShop} and
 * {@code unknownValuesFailClosed}.
 *
 * <p>It was right, and then the product moved underneath it. SINGLE_SHOP stopped
 * being "the mode that changes nothing" the day a second merchant was onboarded
 * and became the WIDE mode: it hands catalogue definition to any CATALOG_MANAGE
 * holder, and it returns from TenantResolver before the branch that gives a
 * platform administrator platform scope. So the old fallback was failing OPEN
 * while its own comment said closed, and {@code PLATFORM_MODE=MULTI_SHOP} - the
 * likeliest typo - silently selected the widest setting there is.
 *
 * <p>The two assertions below are therefore REPLACED rather than deleted, and
 * the reasoning they encoded is kept here so nobody re-derives it from scratch.
 * The rule now: absent means the marketplace, malformed means refuse to start.
 * {@link PlatformModeConfigurationTest} is where it is pinned in full.
 */
@DisplayName("Platform mode")
class PlatformModeTest {

    private static PlatformMode modeFor(String raw) {
        return new PlatformProperties(raw, "SHOP-1", "", "").getMode();
    }

    @Test
    @DisplayName("a deployment that sets nothing runs the marketplace")
    void defaultsToTheMarketplace() {
        // WAS defaultsToSingleShop. See the class comment: on a live
        // marketplace SINGLE_SHOP is the permissive mode, so defaulting to it
        // meant an unset environment variable quietly widened what merchants
        // could do.
        assertEquals(PlatformMode.MULTI_SHOP_PRODUCTION, modeFor(null));
        assertEquals(PlatformMode.MULTI_SHOP_PRODUCTION, modeFor(""));
        assertEquals(PlatformMode.MULTI_SHOP_PRODUCTION, modeFor("   "));
    }

    @Test
    @DisplayName("a typo refuses to start rather than picking the widest mode")
    void unknownValuesRefuseToStart() {
        // WAS unknownValuesFailClosed, which returned SINGLE_SHOP. That is the
        // same swap as above and the more dangerous half of it: MULTI_SHOP is
        // the natural misspelling of MULTI_SHOP_PRODUCTION, and it selected the
        // widest setting with no signal anywhere.
        assertThrows(IllegalStateException.class, () -> modeFor("MULTI_SHOP"));
        assertThrows(IllegalStateException.class, () -> modeFor("multishop"));
        assertThrows(IllegalStateException.class, () -> modeFor("production"));
    }

    @Test
    @DisplayName("the real values parse, whatever the casing or padding")
    void realValuesParse() {
        assertEquals(PlatformMode.MULTI_SHOP_DEMO, modeFor("MULTI_SHOP_DEMO"));
        assertEquals(PlatformMode.MULTI_SHOP_DEMO, modeFor("  multi_shop_demo  "));
        assertEquals(PlatformMode.MULTI_SHOP_PRODUCTION, modeFor("MULTI_SHOP_PRODUCTION"));
    }

    @Test
    @DisplayName("only single-shop resolves the tenant implicitly")
    void onlySingleShopResolvesImplicitly() {
        // This is what keeps the APKs already on customers' phones working:
        // their tokens carry no shop claim, and under SINGLE_SHOP they do not
        // need one. Both marketplace modes must demand an explicit context.
        assertFalse(PlatformMode.SINGLE_SHOP.requiresExplicitShopContext());
        assertTrue(PlatformMode.MULTI_SHOP_DEMO.requiresExplicitShopContext());
        assertTrue(PlatformMode.MULTI_SHOP_PRODUCTION.requiresExplicitShopContext());

        assertFalse(PlatformMode.SINGLE_SHOP.isMultiShop());
        assertTrue(PlatformMode.MULTI_SHOP_DEMO.isMultiShop());
    }

    @Test
    @DisplayName("only an ACTIVE merchant may trade, and only an ACTIVE shop may take orders")
    void lifecycleGatesTrading() {
        for (MerchantStatus s : MerchantStatus.values()) {
            assertEquals(s == MerchantStatus.ACTIVE, s.canTrade(), s.name());
        }
        assertTrue(ShopStatus.ACTIVE.canAcceptOrders());
        assertFalse(ShopStatus.PAUSED.canAcceptOrders(), "a paused shop is visible but closed");
        assertFalse(ShopStatus.SUSPENDED.canAcceptOrders());
        assertFalse(ShopStatus.DRAFT.isVisibleToCustomers(), "a half-built shop must not be listed");
        assertTrue(ShopStatus.PAUSED.isVisibleToCustomers(),
                "a customer should still find the shop and see it is closed");
    }
}
