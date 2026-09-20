package com.gpstore.catalog.shop;

import com.gpstore.platform.Merchant;
import com.gpstore.platform.MerchantRepository;
import com.gpstore.platform.MerchantStatus;
import com.gpstore.platform.PlatformMode;
import com.gpstore.platform.PlatformProperties;
import com.gpstore.platform.Shop;
import com.gpstore.platform.ShopRepository;
import com.gpstore.platform.ShopScopeSwitch;
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
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A merchant reading their own shelf, and finding the right category on it.
 *
 * <p>The screens these back are the ones whose absence made commerce modes
 * unreachable. These are the server-side halves: "show me everything I sell
 * as Visit to Buy" and "find me the category for a phone without scrolling
 * past somebody else's groceries".
 */
@SpringBootTest(properties = {
        "platform.mode=MULTI_SHOP_PRODUCTION",
        "outbox.initial-delay-ms=3600000",
        "outbox.drain-interval-ms=3600000",
        "payment.expiry-initial-delay-ms=3600000",
        "idempotency.cleanup-initial-delay-ms=3600000",
        "otp.cleanup-initial-delay-ms=3600000",
        "delivery.late-flag-initial-delay-ms=3600000"
})
@DisplayName("A merchant finds what they sell")
class AMerchantFindsWhatTheySellTest {

    @Autowired private ShopCatalogueBrowse catalogue;
    @Autowired private CategoryFinder categories;
    @Autowired private ShopScopeSwitch shopScope;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ShopRepository shops;
    @Autowired private MerchantRepository merchants;
    @Autowired private PlatformProperties platform;

    private final String tag = "find" + System.nanoTime();
    private Long merchantId;
    private Long myShop;
    private Long otherShop;
    private Long phoneCategory;
    private Long electronicsCategory;
    private Long groceryCategory;
    private Long carWashCategory;
    private final java.util.List<Long> productIds = new java.util.ArrayList<>();

    @BeforeEach
    void aPhoneShopWithThreeKindsOfListing() {
        TenantDefaults.install(PlatformMode.MULTI_SHOP_PRODUCTION,
                () -> shops.findByCode(platform.getFirstShopCode()).orElseThrow().getId());

        Merchant m = new Merchant();
        m.setLegalName("Find fixture " + tag);
        m.setDisplayName("Find fixture");
        m.setStatus(MerchantStatus.ACTIVE);
        m.setIsDemo(Boolean.TRUE);
        m.setActive(Boolean.TRUE);
        merchantId = merchants.save(m).getId();

        myShop = newShop("mine-" + tag);
        otherShop = newShop("theirs-" + tag);

        electronicsCategory = category("Electronics " + tag, null);
        phoneCategory = category("Mobile Phones " + tag, electronicsCategory);
        groceryCategory = category("Atta, Rice & Dal " + tag, null);
        carWashCategory = category("Car Wash " + tag, null);

        // My shelf: a phone sold online, a gold chain to visit for, a service.
        list(myShop, product("Moto Handset " + tag, phoneCategory), "18000",
                CommerceMode.ONLINE_PURCHASE);
        list(myShop, product("Gold Chain " + tag, phoneCategory), "45000",
                CommerceMode.VISIT_TO_BUY);
        list(myShop, product("Screen Repair " + tag, carWashCategory), "800",
                CommerceMode.SERVICE_AT_SHOP);

        // Somebody else's shelf, in a category I do not sell in.
        list(otherShop, product("Aashirvaad Atta " + tag, groceryCategory), "250",
                CommerceMode.ONLINE_PURCHASE);
    }

    @AfterEach
    void tidyUp() {
        TenantContext.clear();
        for (Long shopId : new Long[] {myShop, otherShop}) {
            jdbc.update("DELETE FROM shop_product_variants WHERE shop_id = ?", shopId);
            jdbc.update("DELETE FROM shops WHERE id = ?", shopId);
        }
        for (Long productId : productIds) {
            jdbc.update("DELETE FROM product_variants WHERE product_id = ?", productId);
            jdbc.update("DELETE FROM products WHERE id = ?", productId);
        }
        jdbc.update("DELETE FROM merchants WHERE id = ?", merchantId);
        jdbc.update("UPDATE categories SET parent_id = NULL WHERE id IN (?,?,?,?)",
                phoneCategory, electronicsCategory, groceryCategory, carWashCategory);
        jdbc.update("DELETE FROM categories WHERE id IN (?,?,?,?)",
                phoneCategory, electronicsCategory, groceryCategory, carWashCategory);
        TenantDefaults.install(platform.getMode(),
                () -> shops.findByCode(platform.getFirstShopCode()).orElseThrow().getId());
    }

    @Nested
    @DisplayName("The shelf, by how it is sold")
    class Catalogue {

        @Test
        @DisplayName("each mode returns only its own listings")
        void eachModeIsSeparate() {
            assertEquals(1, pageOf(CommerceMode.VISIT_TO_BUY).totalElements());
            assertEquals(1, pageOf(CommerceMode.SERVICE_AT_SHOP).totalElements());
            assertEquals(1, pageOf(CommerceMode.ONLINE_PURCHASE).totalElements());
        }

        @Test
        @DisplayName("a row carries the product name, so no second request is needed")
        void theRowIsSelfContained() {
            var item = pageOf(CommerceMode.VISIT_TO_BUY).content().get(0);

            assertTrue(item.name().startsWith("Gold Chain"), item.name());
            assertEquals("VISIT_TO_BUY", item.commerceMode());
            assertEquals("Visit to Buy", item.commerceLabel());
            assertTrue(item.categoryName() != null && item.categoryName().startsWith("Mobile"),
                    "the older listings payload had no name and no category, which is why "
                            + "a screen built on it needed a fetch per row");
        }

        @Test
        @DisplayName("one merchant never sees another's shelf")
        void shelvesAreNotShared() {
            var mine = shopScope.within(myShop,
                    () -> catalogue.page(List.of(CommerceMode.ONLINE_PURCHASE), null, 0, 30));
            assertTrue(mine.content().stream().noneMatch(i -> i.name().contains("Aashirvaad")),
                    "the grocery belongs to the other shop and must not appear here");

            var theirs = shopScope.within(otherShop,
                    () -> catalogue.page(List.of(CommerceMode.ONLINE_PURCHASE), null, 0, 30));
            assertTrue(theirs.content().stream().anyMatch(i -> i.name().contains("Aashirvaad")));
            assertTrue(theirs.content().stream().noneMatch(i -> i.name().contains("Moto")));
        }

        @Test
        @DisplayName("a read with no shop in scope is refused, never defaulted")
        void noScopeIsRefused() {
            TenantContext.clear();
            assertThrows(IllegalStateException.class,
                    () -> catalogue.page(List.of(CommerceMode.VISIT_TO_BUY), null, 0, 30),
                    "a read that fell back to 'some shop' would show one merchant another's "
                            + "shelf, which is the failure this architecture exists to prevent");
        }

        @Test
        @DisplayName("search narrows within the mode, not across it")
        void searchStaysInsideTheMode() {
            var hit = shopScope.within(myShop,
                    () -> catalogue.page(List.of(CommerceMode.VISIT_TO_BUY), "gold", 0, 30));
            assertEquals(1, hit.totalElements());

            var miss = shopScope.within(myShop,
                    () -> catalogue.page(List.of(CommerceMode.VISIT_TO_BUY), "moto", 0, 30));
            assertEquals(0, miss.totalElements(),
                    "the phone is online, so a Visit-to-Buy search must not find it");
        }

        @Test
        @DisplayName("the counts add up to the shelf")
        void countsByMode() {
            var counts = shopScope.within(myShop, catalogue::countsByMode);
            assertEquals(1L, counts.get("ONLINE_PURCHASE"));
            assertEquals(1L, counts.get("VISIT_TO_BUY"));
            assertEquals(1L, counts.get("SERVICE_AT_SHOP"));
        }

        private ShopCatalogueBrowse.CataloguePage pageOf(CommerceMode mode) {
            return shopScope.within(myShop, () -> catalogue.page(List.of(mode), null, 0, 30));
        }
    }

    @Nested
    @DisplayName("Finding a category")
    class Categories {

        @Test
        @DisplayName("typing 'phone' finds the phone category")
        void typingPhoneFindsPhones() {
            var results = shopScope.within(myShop, () -> categories.search("Mobile Phones " + tag, 40));
            assertTrue(results.stream().anyMatch(c -> c.id().equals(phoneCategory)),
                    "a phone merchant typing 'phone' must not have to scroll past groceries");
        }

        @Test
        @DisplayName("typing 'car wash' finds the service category")
        void typingCarWashFindsIt() {
            var results = shopScope.within(myShop, () -> categories.search("Car Wash " + tag, 40));
            assertTrue(results.stream().anyMatch(c -> c.id().equals(carWashCategory)));
        }

        @Test
        @DisplayName("search is case-insensitive, because nobody types capitals")
        void caseDoesNotMatter() {
            var lower = shopScope.within(myShop,
                    () -> categories.search(("Mobile Phones " + tag).toLowerCase(Locale.ROOT), 40));
            var upper = shopScope.within(myShop,
                    () -> categories.search(("Mobile Phones " + tag).toUpperCase(Locale.ROOT), 40));
            assertTrue(lower.stream().anyMatch(c -> c.id().equals(phoneCategory)));
            assertTrue(upper.stream().anyMatch(c -> c.id().equals(phoneCategory)));
        }

        @Test
        @DisplayName("this shop's own categories come first, ahead of everybody else's")
        void myCategoriesLead() {
            var results = shopScope.within(myShop, () -> categories.search(tag, 40));

            assertFalse(results.isEmpty());
            assertTrue(results.get(0).usedByThisShop(),
                    "a phone merchant must not be shown a grocery category above the ones "
                            + "they actually sell in: " + results.get(0).name());

            int lastMine = -1;
            int firstOther = Integer.MAX_VALUE;
            for (int i = 0; i < results.size(); i++) {
                if (results.get(i).usedByThisShop()) {
                    lastMine = i;
                } else {
                    firstOther = Math.min(firstOther, i);
                }
            }
            assertTrue(lastMine < firstOther,
                    "the shop's own categories must be one contiguous band at the top");
        }

        @Test
        @DisplayName("a category carries its parent's name, so two alike are told apart")
        void theParentIsCarried() {
            var results = shopScope.within(myShop, () -> categories.search("Mobile Phones " + tag, 40));
            var phone = results.stream()
                    .filter(c -> c.id().equals(phoneCategory)).findFirst().orElseThrow();

            assertEquals(electronicsCategory, phone.parentId());
            assertTrue(phone.parentName().startsWith("Electronics"),
                    "\"Mobile Phones\" alone is ambiguous in a catalogue that may also have "
                            + "one under Repair; the parent resolves it");
        }

        @Test
        @DisplayName("a blank query is the picker opening, not an error")
        void blankIsBrowsing() {
            var results = shopScope.within(myShop, () -> categories.search("", 40));
            assertFalse(results.isEmpty(),
                    "opening the picker without typing must show something, and it should be "
                            + "this shop's own categories");
            assertTrue(results.get(0).usedByThisShop());
        }

        @Test
        @DisplayName("nothing matched is an empty list, not a failure")
        void noMatchIsAnAnswer() {
            var results = shopScope.within(myShop,
                    () -> categories.search("zzz-no-such-category-" + tag, 40));
            assertTrue(results.isEmpty());
        }

        @Test
        @DisplayName("the children of a parent are reachable")
        void childrenDrillDown() {
            var children = shopScope.within(myShop, () -> categories.childrenOf(electronicsCategory));
            assertEquals(1, children.size());
            assertEquals(phoneCategory, children.get(0).id());
        }

        @Test
        @DisplayName("an apostrophe is a character, not a way into the database")
        void hostileInputIsJustText() {
            var results = shopScope.within(myShop,
                    () -> categories.search("' OR '1'='1", 40));
            assertTrue(results.isEmpty());
            assertTrue(jdbc.queryForObject("SELECT count(*) FROM categories", Long.class) > 0);
        }
    }

    // ------------------------------------------------------------- fixture

    private Long newShop(String code) {
        Shop shop = new Shop();
        shop.setMerchantId(merchantId);
        shop.setCode(code);
        shop.setDisplayName(code);
        shop.setStatus(ShopStatus.ACTIVE);
        shop.setLatitude(21.1);
        shop.setLongitude(79.1);
        shop.setMaxDeliveryRadiusKm(new BigDecimal("10"));
        shop.setTimeZone("Asia/Kolkata");
        shop.setIsDemo(Boolean.TRUE);
        shop.setActive(Boolean.TRUE);
        return shops.save(shop).getId();
    }

    private Long category(String name, Long parentId) {
        jdbc.update("INSERT INTO categories (name, active, parent_id) VALUES (?, true, ?)",
                name, parentId);
        return jdbc.queryForObject("SELECT id FROM categories WHERE name = ?", Long.class, name);
    }

    private Long product(String name, Long categoryId) {
        jdbc.update("INSERT INTO products (name, brand, active, category_id) "
                + "VALUES (?, 'Brand', true, ?)", name, categoryId);
        Long productId = jdbc.queryForObject(
                "SELECT id FROM products WHERE name = ? ORDER BY id DESC LIMIT 1",
                Long.class, name);
        productIds.add(productId);
        jdbc.update("INSERT INTO product_variants "
                + "(product_id, quantity, unit, mrp, selling_price, available, active) "
                + "VALUES (?, 1, 'each', 100, 100, true, true)", productId);
        return jdbc.queryForObject(
                "SELECT id FROM product_variants WHERE product_id = ? ORDER BY id LIMIT 1",
                Long.class, productId);
    }

    private void list(Long shopId, Long variantId, String price, CommerceMode mode) {
        jdbc.update("INSERT INTO shop_product_variants "
                        + "(shop_id, product_variant_id, selling_price, mrp, available, active, "
                        + " commerce_mode, price_mode, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, true, true, ?, ?, now(), now())",
                shopId, variantId, new BigDecimal(price), new BigDecimal(price), mode.name(),
                mode.isBuyableOnline() ? "EXACT_PRICE" : "STARTING_FROM");
    }
}
