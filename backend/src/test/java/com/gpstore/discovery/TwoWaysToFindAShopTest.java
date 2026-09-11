package com.gpstore.discovery;

import com.gpstore.exception.BadRequestException;
import com.gpstore.exception.ResourceNotFoundException;
import com.gpstore.platform.Merchant;
import com.gpstore.platform.MerchantRepository;
import com.gpstore.platform.MerchantStatus;
import com.gpstore.platform.PlatformMode;
import com.gpstore.platform.PlatformProperties;
import com.gpstore.platform.Shop;
import com.gpstore.platform.ShopDiscovery;
import com.gpstore.platform.ShopRepository;
import com.gpstore.platform.ShopStatus;
import com.gpstore.platform.TenantContext;
import com.gpstore.platform.TenantDefaults;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MY SHOPS, OR THE BEST PRICE — AND THE CUSTOMER PICKS WHICH (Part 2 §3-§6).
 *
 * <p>THREE SHOPS, because §20 asks for A, B and C and because two is the
 * number at which an ordering bug hides: with two shops, "preferred first"
 * and "reversed" look identical half the time.
 *
 * <p>THE RULE THAT IS EASIEST TO BREAK is §4's last line — "do not silently
 * override the customer's explicit preference simply because another shop is
 * cheaper". It is easy to break because overriding it feels like helping.
 * Most of {@link Preferences} below exists to hold that in place: the other
 * shops must still be in the list, in their own order, and a preference must
 * never remove one.
 */
@SpringBootTest(properties = {
        "platform.mode=MULTI_SHOP_PRODUCTION",
        "marketplace.search.radii-km=3,8,15,30",
        "outbox.initial-delay-ms=3600000",
        "outbox.drain-interval-ms=3600000",
        "payment.expiry-initial-delay-ms=3600000",
        "idempotency.cleanup-initial-delay-ms=3600000",
        "otp.cleanup-initial-delay-ms=3600000",
        "delivery.late-flag-initial-delay-ms=3600000"
})
@DisplayName("Two ways to find a shop")
class TwoWaysToFindAShopTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private ShopRepository shops;
    @Autowired private MerchantRepository merchants;
    @Autowired private PlatformProperties platform;
    @Autowired private PreferredShops preferred;
    @Autowired private ShopDiscovery discovery;
    @Autowired private com.gpstore.platform.SearchRadiusLadder ladder;
    @Autowired private com.gpstore.cart.CartByShop cartByShop;

    private final String tag = "pref" + System.nanoTime();

    /** Gorakhpur, roughly - the same pin the storefront tests use. */
    private static final double LAT = 26.7606;
    private static final double LNG = 83.3732;

    private long shopA;
    private long shopB;
    private long shopC;
    private Long merchantId;
    private Long customerId;
    private Long otherCustomerId;
    private Long kirana;
    private Long hardware;

    @BeforeEach
    void threeShopsAndTwoCategories() {
        TenantDefaults.install(PlatformMode.MULTI_SHOP_PRODUCTION,
                () -> shops.findByCode(platform.getFirstShopCode()).orElseThrow().getId());
        shopA = shops.findByCode(platform.getFirstShopCode()).orElseThrow().getId();

        Merchant m = new Merchant();
        m.setLegalName("Preference fixture " + tag);
        m.setDisplayName("Preference fixture");
        m.setStatus(MerchantStatus.ACTIVE);
        m.setIsDemo(Boolean.TRUE);
        m.setActive(Boolean.TRUE);
        merchantId = merchants.save(m).getId();

        shopB = newShop("PRB-" + tag, "The kirana two streets over", 1.2);
        shopC = newShop("PRC-" + tag, "The hardware shop", 6.0);

        customerId = newCustomer("chooser");
        otherCustomerId = newCustomer("bystander");

        kirana = newCategory("Kirana " + tag);
        hardware = newCategory("Hardware " + tag);
    }

    @AfterEach
    void tidyUp() {
        TenantContext.clear();
        jdbc.update("DELETE FROM customer_preferred_shops WHERE customer_id IN (?, ?)",
                customerId, otherCustomerId);
        jdbc.update("DELETE FROM customers WHERE id IN (?, ?)", customerId, otherCustomerId);
        for (long shop : List.of(shopB, shopC)) {
            jdbc.update("DELETE FROM store_operations_settings WHERE shop_id = ?", shop);
            jdbc.update("DELETE FROM delivery_pricing_settings WHERE shop_id = ?", shop);
            jdbc.update("DELETE FROM shops WHERE id = ?", shop);
        }
        jdbc.update("DELETE FROM merchants WHERE id = ?", merchantId);
        jdbc.update("DELETE FROM categories WHERE id IN (?, ?)", kirana, hardware);
        // Back to SINGLE_SHOP, not to this class's own mode - TenantDefaults
        // is a static holder shared by every cached context in the run.
        TenantDefaults.install(PlatformMode.SINGLE_SHOP,
                () -> shops.findByCode(platform.getFirstShopCode()).orElseThrow().getId());
    }

    // ----------------------------------------------------------------- §4

    @Nested
    @DisplayName("§4 preferences are per category, and up to two")
    class Preferences {

        @Test
        @DisplayName("the kirana I trust has no opinion about screws")
        void preferencesAreNotGlobal() {
            preferred.setForCategory(customerId, kirana, List.of(shopA, shopB));
            preferred.setForCategory(customerId, hardware, List.of(shopC));

            assertEquals(List.of(shopA, shopB), preferred.forCategory(customerId, kirana));
            assertEquals(List.of(shopC), preferred.forCategory(customerId, hardware),
                    "A SINGLE GLOBAL PREFERRED SHOP is the design that looks obvious and "
                            + "fails the first week somebody buys atta and screws.");

            Map<Long, List<Long>> all = preferred.all(customerId);
            assertEquals(2, all.size());
        }

        @Test
        @DisplayName("a third choice has nowhere to go")
        void twoIsTheLimit() {
            BadRequestException refused = assertThrows(BadRequestException.class,
                    () -> preferred.setForCategory(customerId, kirana,
                            List.of(shopA, shopB, shopC)));
            assertTrue(refused.getMessage().contains("2"),
                    "and it says what the limit is: " + refused.getMessage());
            assertTrue(preferred.forCategory(customerId, kirana).isEmpty(),
                    "A REFUSED SAVE MUST NOT HAVE HAPPENED HALFWAY - two of the three left "
                            + "behind would be a preference the customer never chose.");
        }

        @Test
        @DisplayName("the same shop twice is one choice, not two")
        void duplicatesCollapse() {
            preferred.setForCategory(customerId, kirana, List.of(shopA, shopA));
            assertEquals(List.of(shopA), preferred.forCategory(customerId, kirana),
                    "\"PICK TWO\" AND GET ONE would read to the ranking as a stronger "
                            + "preference than it is.");
        }

        @Test
        @DisplayName("the order is the customer's, and re-saving replaces it")
        void firstChoiceIsFirst() {
            preferred.setForCategory(customerId, kirana, List.of(shopA, shopB));
            assertEquals(List.of(shopA, shopB), preferred.forCategory(customerId, kirana));

            preferred.setForCategory(customerId, kirana, List.of(shopB, shopA));
            assertEquals(List.of(shopB, shopA), preferred.forCategory(customerId, kirana),
                    "swapping first and second choice is a thing a customer does, and the "
                            + "unique index on (customer, category, slot) is the thing that "
                            + "makes it awkward - so the service clears before it writes");
        }

        @Test
        @DisplayName("an empty list is how \"no preference\" is said")
        void clearingIsSayable() {
            preferred.setForCategory(customerId, kirana, List.of(shopA));
            preferred.setForCategory(customerId, kirana, List.of());
            assertTrue(preferred.forCategory(customerId, kirana).isEmpty());
        }

        @Test
        @DisplayName("a shop customers cannot open cannot be preferred")
        void onlyBrowsableShops() {
            jdbc.update("UPDATE shops SET status = 'SUSPENDED' WHERE id = ?", shopC);
            assertThrows(BadRequestException.class,
                    () -> preferred.setForCategory(customerId, hardware, List.of(shopC)),
                    "PREFERRING A SUSPENDED SHOP would put a storefront the customer cannot "
                            + "open at the top of their own list, with no way to tell why.");
            jdbc.update("UPDATE shops SET status = 'ACTIVE' WHERE id = ?", shopC);
        }

        @Test
        @DisplayName("a category that does not exist is not a preference")
        void theCategoryMustBeReal() {
            assertThrows(ResourceNotFoundException.class,
                    () -> preferred.setForCategory(customerId, 999_999_999L, List.of(shopA)));
        }

        @Test
        @DisplayName("my preferences are mine")
        void preferencesAreNotShared() {
            preferred.setForCategory(customerId, kirana, List.of(shopA, shopB));
            assertTrue(preferred.forCategory(otherCustomerId, kirana).isEmpty(),
                    "one customer's shopping habits are not another's, and there is "
                            + "deliberately no query in the repository that answers \"who "
                            + "prefers my shop\"");
        }
    }

    // -------------------------------------------------- §4's harder half

    @Nested
    @DisplayName("§4 a preference orders the list; it never shortens it")
    class OrderingNotFiltering {

        @Test
        @DisplayName("the shops I did not pick are still there")
        void nothingIsHidden() {
            preferred.setForCategory(customerId, kirana, List.of(shopC));

            List<Long> all = List.of(shopA, shopB, shopC);
            List<Long> ordered = preferred.preferredFirst(customerId, kirana, all, id -> id);

            assertEquals(shopC, ordered.get(0), "the preferred shop comes first");
            assertEquals(3, ordered.size(),
                    "AND THE OTHER TWO ARE STILL IN THE LIST. §4 requires that a customer in "
                            + "this mode can still see, compare, switch and buy elsewhere - "
                            + "so this reorders and never filters.");
            assertTrue(ordered.containsAll(all));
        }

        @Test
        @DisplayName("with no preference the list keeps the order it had")
        void distanceOrderSurvives() {
            List<Long> byDistance = List.of(shopA, shopB, shopC);
            assertEquals(byDistance,
                    preferred.preferredFirst(customerId, kirana, byDistance, id -> id),
                    "a customer who has chosen nothing is shown nearest-first, not shuffled");
        }

        @Test
        @DisplayName("second choice outranks a shop I never picked")
        void bothSlotsRankAboveTheRest() {
            preferred.setForCategory(customerId, kirana, List.of(shopC, shopB));
            List<Long> ordered = preferred.preferredFirst(
                    customerId, kirana, List.of(shopA, shopB, shopC), id -> id);
            assertEquals(List.of(shopC, shopB, shopA), ordered);
        }
    }

    // ----------------------------------------------------------------- §6

    @Nested
    @DisplayName("§6 local-first is not local-only")
    class ProgressiveRadius {

        @Test
        @DisplayName("the ladder is the one this deployment configured")
        void theConfiguredLadderIsUsed() {
            assertEquals(List.of(new BigDecimal("3"), new BigDecimal("8"),
                            new BigDecimal("15"), new BigDecimal("30")),
                    ladder.rungs(),
                    "set by marketplace.search.radii-km on this test class - which is the "
                            + "whole point of it no longer being a constant");
        }

        @Test
        @DisplayName("a search that finds nothing nearby widens, and says so")
        void itWidensAndExplains() {
            // A pin far enough from every fixture shop that the first rung is
            // empty, but inside a later one.
            ShopDiscovery.RadiusSearch search =
                    discovery.searchOutwards(LAT + 0.09, LNG + 0.09, new BigDecimal("3"));

            if (search.shops().isEmpty()) {
                // Nothing at any rung - still a legitimate answer, and it must
                // not claim to have widened successfully.
                assertNull(search.message());
                return;
            }

            assertTrue(search.searchedKm().compareTo(search.askedKm()) >= 0,
                    "a widened search never reports a NARROWER circle than the one asked for");
            if (search.widened()) {
                assertNotNull(search.message());
                assertTrue(search.message().startsWith("No shops within 3 km."),
                        "§6'S SENTENCE, WRITTEN BY THE SERVER: " + search.message()
                                + " - a client rebuilding it from two numbers eventually "
                                + "rebuilds it wrongly, most likely as \"no shops nearby\" "
                                + "when there are twelve, two rungs out.");
                assertTrue(search.message().contains("Showing shops within"));
            }
        }

        @Test
        @DisplayName("an unwidened search has nothing to explain")
        void noMessageWhenNothingWidened() {
            ShopDiscovery.RadiusSearch search =
                    discovery.searchOutwards(LAT, LNG, new BigDecimal("30"));
            assertFalse(search.widened());
            assertNull(search.message(),
                    "a banner saying \"showing shops within 30 km\" on a search that asked "
                            + "for 30 km is noise");
        }

        @Test
        @DisplayName("a client cannot search past the top of the ladder")
        void theTopIsTheTop() {
            ShopDiscovery.RadiusSearch absurd =
                    discovery.searchOutwards(LAT, LNG, new BigDecimal("100000"));
            assertEquals(0, absurd.askedKm().compareTo(ladder.max()),
                    "clamped to the ladder's top rung, not trusted (§78)");
        }
    }

    // ---------------------------------------------------------------- §13

    @Nested
    @DisplayName("§13 the basket is several purchases, and is drawn as several")
    class TheBasket {

        @Test
        @DisplayName("the combined figure is named for what it is")
        void theCombinedTotalIsNotCalledTotal() {
            var basket = cartByShop.forCustomer(customerId, null);

            assertNotNull(basket);
            assertTrue(basket.isSinglePayment(),
                    "an empty or single-shop basket genuinely is one payment");

            // THE FIELD NAME IS THE SAFEGUARD. §13 allows a combined figure
            // "informationally" and forbids it looking like one merchant
            // checkout - and a field called "total" on a cart response is an
            // invitation to draw it large and put a Pay button under it.
            List<String> components = java.util.Arrays.stream(
                            com.gpstore.cart.CartByShop.Basket.class.getRecordComponents())
                    .map(java.lang.reflect.RecordComponent::getName).toList();
            assertTrue(components.contains("informationalCombinedTotal"), components.toString());
            assertFalse(components.contains("total"),
                    "there must be no bare \"total\" on a multi-shop basket: " + components);
            assertTrue(components.contains("isSinglePayment"),
                    "and whether it is one payment is stated, not left to be inferred from "
                            + "the section count: " + components);
        }

        @Test
        @DisplayName("with no address, delivery is unknown rather than free")
        void unknownIsNotZero() {
            var basket = cartByShop.forCustomer(customerId, null);
            assertTrue(basket.shops().isEmpty() || basket.shops().stream()
                            .noneMatch(com.gpstore.cart.CartByShop.ShopSection::deliveryKnown),
                    "A SHOP THAT CANNOT QUOTE HAS NOT OFFERED FREE DELIVERY. Showing zero "
                            + "would understate the basket and surprise the customer at "
                            + "checkout, which is the thing §7 forbids.");
        }
    }

    // ------------------------------------------------------------- fixtures

    private long newShop(String code, String name, double radiusKm) {
        Shop s = new Shop();
        s.setMerchantId(merchantId);
        s.setCode(code);
        s.setDisplayName(name);
        s.setStatus(ShopStatus.ACTIVE);
        s.setLatitude(LAT);
        s.setLongitude(LNG);
        s.setMaxDeliveryRadiusKm(BigDecimal.valueOf(radiusKm));
        s.setTimeZone("Asia/Kolkata");
        s.setIsDemo(Boolean.TRUE);
        s.setActive(Boolean.TRUE);
        return shops.save(s).getId();
    }

    private Long newCustomer(String who) {
        String mobile = "9" + String.format("%09d",
                Math.abs(System.nanoTime() % 1_000_000_000L));
        String email = who + "." + tag + "@example.test";
        jdbc.update("INSERT INTO customers (full_name, email, mobile_number, active) "
                + "VALUES (?, ?, ?, true)", who + " " + tag, email, mobile);
        return jdbc.queryForObject("SELECT id FROM customers WHERE email = ?", Long.class, email);
    }

    private Long newCategory(String name) {
        jdbc.update("INSERT INTO categories (name, active) VALUES (?, true)", name);
        return jdbc.queryForObject("SELECT id FROM categories WHERE name = ?", Long.class, name);
    }
}
