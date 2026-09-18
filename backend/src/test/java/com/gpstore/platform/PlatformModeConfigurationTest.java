package com.gpstore.platform;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What GP-STORE is when nobody says, and what it does when somebody says it
 * wrong.
 *
 * <p>THE REASONING THAT WENT STALE. PlatformProperties defaulted to
 * SINGLE_SHOP, and its comment said "failing closed matters more than failing
 * loudly here ... SINGLE_SHOP is the mode that changes nothing". That was true
 * when there was one shop. It is the opposite of true now: on a marketplace,
 * SINGLE_SHOP is the LOOSE mode. It hands catalogue definition to anyone
 * holding CATALOG_MANAGE, it resolves every credential that has no shop of its
 * own to Shop #1, and it makes the tenant resolver stop before the branch that
 * gives a platform administrator platform scope.
 *
 * <p>So the old default and the old typo-fallback both pointed at the
 * permissive answer. A blank {@code PLATFORM_MODE}, or {@code MULTI_SHOP}
 * misspelt, silently widened what merchants could do. That is fail-open
 * wearing the words "fail closed".
 *
 * <p>The rule this file pins instead: <b>absent means strict, malformed means
 * refuse to start.</b> A deployment that cannot say what it is does not get to
 * be the permissive one, and a typo gets a stack trace at boot rather than a
 * quietly wider marketplace.
 */
@DisplayName("What GP-STORE is when the configuration does not say")
class PlatformModeConfigurationTest {

    @Nested
    @DisplayName("when nothing is configured")
    class Absent {

        @Test
        @DisplayName("an unset mode is the marketplace, not one shop")
        void unsetIsTheMarketplace() {
            assertEquals(PlatformMode.MULTI_SHOP_PRODUCTION, modeOf(null),
                    "production runs with platform.mode unset. Defaulting that to "
                            + "SINGLE_SHOP is what let a merchant define the shared taxonomy "
                            + "and what stopped Super Admin resolving to platform scope.");
        }

        @Test
        @DisplayName("and so is a blank one, which is what an empty env var looks like")
        void blankIsTheMarketplaceToo() {
            assertEquals(PlatformMode.MULTI_SHOP_PRODUCTION, modeOf(""));
            assertEquals(PlatformMode.MULTI_SHOP_PRODUCTION, modeOf("   "));
        }

        @Test
        @DisplayName("the strict default is genuinely the strict one")
        void theDefaultIsTheStrictOne() {
            // A guard on the claim above rather than on the value: if
            // isMultiShop ever stopped meaning "narrower", this default would
            // silently become the wrong choice and nothing else here would say
            // so.
            assertTrue(modeOf(null).isMultiShop(),
                    "the default must be the mode that requires explicit shop context and "
                            + "treats catalogue definition as a platform act");
            assertTrue(modeOf(null).requiresExplicitShopContext());
        }
    }

    @Nested
    @DisplayName("when it is configured wrong")
    class Malformed {

        @Test
        @DisplayName("a typo refuses to start rather than picking a mode")
        void aTypoRefusesToStart() {
            IllegalStateException refused = assertThrows(IllegalStateException.class,
                    () -> modeOf("MULTI_SHOP"),
                    "MULTI_SHOP is the most likely typo of MULTI_SHOP_PRODUCTION, and it "
                            + "used to be silently read as SINGLE_SHOP - the widest setting "
                            + "there is. A deployment misconfigured this way must not boot.");
            assertTrue(refused.getMessage().contains("MULTI_SHOP"),
                    "the error has to name what was actually set, or nobody can fix it: "
                            + refused.getMessage());
            assertTrue(refused.getMessage().contains("SINGLE_SHOP")
                            && refused.getMessage().contains("MULTI_SHOP_PRODUCTION"),
                    "and it has to list what is allowed: " + refused.getMessage());
        }

        @Test
        @DisplayName("any other nonsense refuses too")
        void anyNonsenseRefuses() {
            assertThrows(IllegalStateException.class, () -> modeOf("marketplace"));
            assertThrows(IllegalStateException.class, () -> modeOf("true"));
            assertThrows(IllegalStateException.class, () -> modeOf("1"));
        }
    }

    @Nested
    @DisplayName("when it is configured properly")
    class Explicit {

        @Test
        @DisplayName("each mode is taken at its word")
        void eachModeIsHonoured() {
            assertEquals(PlatformMode.SINGLE_SHOP, modeOf("SINGLE_SHOP"));
            assertEquals(PlatformMode.MULTI_SHOP_DEMO, modeOf("MULTI_SHOP_DEMO"));
            assertEquals(PlatformMode.MULTI_SHOP_PRODUCTION, modeOf("MULTI_SHOP_PRODUCTION"));
        }

        @Test
        @DisplayName("case and surrounding space do not matter")
        void spellingIsForgivingWhereItSafelyCanBe() {
            // Forgiving about shape, never about meaning: "multi_shop_production"
            // is unambiguous, "MULTI_SHOP" is not and is refused above.
            assertEquals(PlatformMode.MULTI_SHOP_PRODUCTION, modeOf("  multi_shop_production  "));
            assertEquals(PlatformMode.SINGLE_SHOP, modeOf("single_shop"));
        }

        @Test
        @DisplayName("SINGLE_SHOP remains available, and remains a deliberate choice")
        void singleShopIsStillReachable() {
            // NOT REMOVED. A genuine one-shop deployment of this codebase is a
            // real thing and the mode still serves it. What changed is that you
            // now have to ask for it: it is no longer what you get by saying
            // nothing.
            assertEquals(PlatformMode.SINGLE_SHOP, modeOf("SINGLE_SHOP"));
            assertTrue(!modeOf("SINGLE_SHOP").isMultiShop());
        }
    }

    private static PlatformMode modeOf(String raw) {
        return new PlatformProperties(raw, "SHOP-1", "", "").getMode();
    }
}
