package com.gpstore.platform;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Which mode a deployment runs in, and what happens when nobody says.
 *
 * FAILING CLOSED IS THE WHOLE POINT. A typo in an environment variable must
 * not be able to put a live deployment into a marketplace mode it was never
 * configured for - so anything unrecognised, blank or absent lands on
 * SINGLE_SHOP, the mode that changes nothing.
 */
@DisplayName("Platform mode")
class PlatformModeTest {

    private static PlatformMode modeFor(String raw) {
        return new PlatformProperties(raw, "SHOP-1", "", "").getMode();
    }

    @Test
    @DisplayName("a deployment that sets nothing behaves exactly as it does today")
    void defaultsToSingleShop() {
        assertEquals(PlatformMode.SINGLE_SHOP, modeFor(null));
        assertEquals(PlatformMode.SINGLE_SHOP, modeFor(""));
        assertEquals(PlatformMode.SINGLE_SHOP, modeFor("   "));
    }

    @Test
    @DisplayName("a typo falls back to single shop rather than throwing or guessing")
    void unknownValuesFailClosed() {
        assertEquals(PlatformMode.SINGLE_SHOP, modeFor("MULTI_SHOP"));
        assertEquals(PlatformMode.SINGLE_SHOP, modeFor("multishop"));
        assertEquals(PlatformMode.SINGLE_SHOP, modeFor("production"));
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
