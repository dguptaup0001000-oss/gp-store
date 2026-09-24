package com.gpstore.discovery;

import com.gpstore.platform.MerchantLifecycleService;
import com.gpstore.platform.MerchantStatus;
import com.gpstore.platform.PlatformMode;
import com.gpstore.platform.PlatformProperties;
import com.gpstore.platform.ShopLifecycleService;
import com.gpstore.platform.ShopRepository;
import com.gpstore.platform.ShopStatus;
import com.gpstore.platform.TenantDefaults;
import com.gpstore.support.CatalogueItem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * "Who near me sells medicine?" - the question the home screen is built on.
 *
 * <p>WHAT MAKES A SHOP A CHEMIST HERE. Nothing declares it. GP-STORE has no
 * column saying which categories a shop trades in, and deliberately gains
 * none: a shop belongs to a category when it is listing something orderable
 * in it, which is a fact already sitting in shop_product_variants and which
 * stays true on its own. A merchant who stops stocking medicine leaves the
 * Medicine screen when their last listing goes, with nobody remembering to
 * clear a tag.
 *
 * <p>THE TEST THAT MATTERS MOST IS {@code theLadderKeepsClimbingForACategory}.
 * The tempting implementation filters the ANSWER: ask the ladder for shops,
 * then drop the ones that do not sell the category. That looks right and is
 * wrong in the one case customers actually hit - a customer whose nearest
 * rung is full of kiranas and who wants a chemist is told "no shops found",
 * and "search farther" cannot help them because the search already succeeded.
 * Narrowing has to happen INSIDE the loop, one rung at a time, and that test
 * is what says so.
 */
@SpringBootTest(properties = {
        // Without this the resolver short-circuits to Shop #1 and every shop
        // below is the same shop under three names - see AShopStocksItsOwnShelfTest.
        "platform.mode=MULTI_SHOP_PRODUCTION",
        "outbox.initial-delay-ms=3600000",
        "outbox.drain-interval-ms=3600000",
        "payment.expiry-initial-delay-ms=3600000",
        "idempotency.cleanup-initial-delay-ms=3600000",
        "otp.cleanup-initial-delay-ms=3600000",
        "delivery.late-flag-initial-delay-ms=3600000"
})
@AutoConfigureMockMvc
@DisplayName("Finding the shops that sell what you came for")
class CategoryDiscoveryTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ShopRepository shops;
    @Autowired private PlatformProperties platform;
    @Autowired private MerchantLifecycleService merchantLifecycle;
    @Autowired private ShopLifecycleService shopLifecycle;
    @Autowired private com.gpstore.repository.CategoryRepository categories;
    @Autowired private com.gpstore.repository.ProductRepository products;
    @Autowired private com.gpstore.repository.ProductVariantRepository variants;

    private final String tag = "catdisc" + System.nanoTime();

    // A pin nothing else in the suite uses, so the shops this test creates are
    // the only ones in range of it and an assertion about "the shops near
    // here" is about this test's own fixture.
    private static final double LAT = 19.076090;
    private static final double LNG = 72.877426;

    private long kirana;
    private long chemist;
    private long farHardware;
    private Long merchantKirana;
    private Long merchantChemist;
    private Long merchantFar;

    private CatalogueItem groceryItem;
    private CatalogueItem medicineItem;
    private CatalogueItem hardwareItem;

    @BeforeEach
    void threeShopsEachSellingSomethingDifferent() {
        TenantDefaults.install(PlatformMode.MULTI_SHOP_PRODUCTION,
                () -> shops.findByCode(platform.getFirstShopCode()).orElseThrow().getId());

        groceryItem = CatalogueItem.create(tag + "groc", jdbc, categories, products, variants);
        medicineItem = CatalogueItem.create(tag + "med", jdbc, categories, products, variants);
        hardwareItem = CatalogueItem.create(tag + "hard", jdbc, categories, products, variants);

        merchantKirana = newMerchant("kirana");
        merchantChemist = newMerchant("chemist");
        merchantFar = newMerchant("far");

        // Both at the pin, both willing to come 5 km, so both serve it.
        kirana = newShop(merchantKirana, "CDK-" + tag, LAT, LNG, "5");
        chemist = newShop(merchantChemist, "CDC-" + tag, LAT, LNG, "5");

        // ROUGHLY 11 KM NORTH and willing to come 30, so it serves the pin but
        // sits outside the ladder's first rung. That gap is what makes the
        // widening test mean something.
        farHardware = newShop(merchantFar, "CDF-" + tag, LAT + 0.10, LNG, "30");

        list(kirana, groceryItem.variantId());
        list(chemist, medicineItem.variantId());
        list(farHardware, hardwareItem.variantId());
    }

    @AfterEach
    void tidyUp() {
        for (long shop : new long[]{kirana, chemist, farHardware}) {
            jdbc.update("DELETE FROM inventory WHERE shop_id = ?", shop);
            jdbc.update("DELETE FROM shop_product_variants WHERE shop_id = ?", shop);
            jdbc.update("DELETE FROM shop_business_hours WHERE shop_id = ?", shop);
            jdbc.update("DELETE FROM shop_staff WHERE shop_id = ?", shop);
            jdbc.update("DELETE FROM delivery_pricing_settings WHERE shop_id = ?", shop);
            jdbc.update("DELETE FROM store_operations_settings WHERE shop_id = ?", shop);
            jdbc.update("DELETE FROM shops WHERE id = ?", shop);
        }
        groceryItem.remove();
        medicineItem.remove();
        hardwareItem.remove();
        jdbc.update("DELETE FROM merchants WHERE id in (?, ?, ?)",
                merchantKirana, merchantChemist, merchantFar);
        TenantDefaults.install(PlatformMode.SINGLE_SHOP,
                () -> shops.findByCode(platform.getFirstShopCode()).orElseThrow().getId());
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("a category shows the shops that sell it and not the ones that do not")
    void aCategoryShowsItsSellers() throws Exception {
        String medicine = discovery("&categoryId=" + medicineItem.categoryId());

        assertTrue(medicine.contains("\"shopId\":" + chemist),
                "the chemist lists the only medicine near this pin and was not offered: " + medicine);
        assertFalse(medicine.contains("\"shopId\":" + kirana),
                "the kirana sells no medicine and was offered under Medicine anyway: " + medicine);
    }

    @Test
    @DisplayName("asking for no category is the list every released app already gets")
    void noCategoryIsUnchanged() throws Exception {
        // BACKWARD COMPATIBILITY, AS A TEST. An app in a customer's hand today
        // calls /discovery with no categoryId, and must keep getting every
        // shop that serves the pin - the new parameter narrows, it never
        // becomes a requirement.
        String all = discovery("");

        assertTrue(all.contains("\"shopId\":" + kirana), all);
        assertTrue(all.contains("\"shopId\":" + chemist), all);
    }

    @Test
    @DisplayName("the ladder keeps climbing until it finds a rung that sells the category")
    void theLadderKeepsClimbingForACategory() throws Exception {
        // THE ONE THAT CATCHES FILTERING-THE-ANSWER. Two shops sit on the
        // first rung and neither sells hardware; the only hardware shop is
        // eleven kilometres out. An implementation that narrows after the
        // search answers "no shops" here, because the search itself succeeded.
        String hardware = discovery("&radiusKm=2&categoryId=" + hardwareItem.categoryId());

        assertTrue(hardware.contains("\"shopId\":" + farHardware),
                "the ladder stopped at a rung with no hardware shop on it instead of "
                        + "climbing to the one that had: " + hardware);
        assertTrue(hardware.contains("\"widened\":true"),
                "the customer was not told the search had been widened: " + hardware);
    }

    @Test
    @DisplayName("a category nobody near you sells comes back empty rather than wrong")
    void anUnsoldCategoryIsEmpty() throws Exception {
        // A real category with a real id, stocked by nobody in range. Empty is
        // the honest answer; the alternative is offering shops that sell
        // nothing the customer came for.
        CatalogueItem onNobodysShelf =
                CatalogueItem.create(tag + "empty", jdbc, categories, products, variants);
        try {
            String none = discovery("&categoryId=" + onNobodysShelf.categoryId());
            assertFalse(none.contains("\"shopId\":" + kirana), none);
            assertFalse(none.contains("\"shopId\":" + chemist), none);
            assertFalse(none.contains("\"shopId\":" + farHardware), none);
        } finally {
            // The whole item, not just its category - create() makes a product
            // and a variant that hang off it, and a category delete alone is a
            // foreign key violation rather than a cleanup.
            onNobodysShelf.remove();
        }
    }

    @Test
    @DisplayName("delisting the last item takes the shop out of that category")
    void delistingRemovesTheShop() throws Exception {
        jdbc.update("UPDATE shop_product_variants SET available = false "
                + "WHERE shop_id = ? AND product_variant_id = ?", chemist, medicineItem.variantId());

        String medicine = discovery("&categoryId=" + medicineItem.categoryId());

        // NOTHING WAS TAGGED OR UNTAGGED. The shop left the category because
        // it stopped listing the only thing it had there, which is the whole
        // argument for deriving this rather than storing it.
        assertFalse(medicine.contains("\"shopId\":" + chemist),
                "a shop that delisted its only medicine is still shown under Medicine: " + medicine);
    }

    @Test
    @DisplayName("the categories offered are the ones somebody nearby actually stocks")
    void categoriesNearMe() throws Exception {
        String near = mockMvc.perform(get("/api/marketplace/categories?lat=" + LAT + "&lng=" + LNG))
                .andReturn().getResponse().getContentAsString();

        assertTrue(near.contains("\"categoryId\":" + groceryItem.categoryId()), near);
        assertTrue(near.contains("\"categoryId\":" + medicineItem.categoryId()), near);
        // The hardware shop serves this pin from 11 km, so its category counts
        // too - "near me" is whoever will deliver, not whoever is close.
        assertTrue(near.contains("\"categoryId\":" + hardwareItem.categoryId()), near);
    }

    @Test
    @DisplayName("the category list says nothing about prices, stock or merchants")
    void theCategoryListLeaksNothing() throws Exception {
        // THE PROJECTION IS THE SAFETY. ShopCategoryPresence is the one
        // customer-facing read that spans shops with the tenant filter off, so
        // what it selects is what a public caller can learn. "Shop 6 sells
        // groceries" is already public. A price, a cost or a stock level is
        // not, and none may appear here.
        String near = mockMvc.perform(get("/api/marketplace/categories?lat=" + LAT + "&lng=" + LNG))
                .andReturn().getResponse().getContentAsString();

        for (String forbidden : new String[]{
                "sellingPrice", "costPrice", "mrp", "stock", "merchantId", "legalName"}) {
            assertFalse(near.contains(forbidden),
                    "the public category list exposed '" + forbidden + "': " + near);
        }
    }

    @Test
    @DisplayName("a pin nobody delivers to has nothing to buy, and says so")
    void nowhereToBuy() throws Exception {
        // The middle of the Bay of Bengal. Empty is an answer, not an error.
        String none = mockMvc.perform(get("/api/marketplace/categories?lat=15.0&lng=88.0"))
                .andReturn().getResponse().getContentAsString();

        assertTrue(none.replaceAll("\\s", "").equals("[]"),
                "a pin no shop serves was given categories to browse: " + none);
    }

    @Test
    @DisplayName("a shop nobody has rated is unrated, not nought out of five")
    void anUnratedShopIsNotZeroStars() throws Exception {
        // ZERO AND NONE ARE DIFFERENT FACTS about a real merchant, and the
        // difference decides whether a customer opens the shop at all. A new
        // kirana with no ratings drawn as 0.0 stars is a shop the app has
        // libelled; the honest answer is that nobody has said anything yet.
        String all = discovery("");

        assertTrue(all.contains("\"ratingCount\":0"),
                "a shop with no ratings did not report a count of zero: " + all);
        assertTrue(all.contains("\"ratingAverage\":null"),
                "an unrated shop was given an average anyway, which every screen "
                        + "would then draw as nought out of five: " + all);
    }

    @Test
    @DisplayName("what customers said is on the list, not only on the shop page")
    void starsAreOnTheList() throws Exception {
        // ONE RATING PER ORDER is the schema's rule (§22: a star has to be
        // earned by a transaction), so each of these names its own order id.
        rate(chemist, 5);
        rate(chemist, 4);
        try {
            String all = discovery("");

            // 4.5 from a five and a four, rounded the same way a shop's own
            // page rounds it - a card saying 4.5 beside a page saying 4.45
            // reads as a bug in both.
            assertTrue(all.contains("\"ratingAverage\":4.5"),
                    "the discovery list did not carry the shop's stars: " + all);
            assertTrue(all.contains("\"ratingCount\":2"), all);
        } finally {
            jdbc.update("DELETE FROM shop_ratings WHERE shop_id = ?", chemist);
        }
    }

    // ------------------------------------------------------------------

    /**
     * One rating against this shop, from a customer, for its own order.
     *
     * The order id is synthetic and unique to this test - shop_ratings only
     * requires one to be present, which is what enforces "one star per
     * transaction" without this fixture having to drive a whole checkout.
     */
    private void rate(long shopId, int stars) {
        Long customerId = jdbc.queryForObject(
                "SELECT id FROM customers ORDER BY id LIMIT 1", Long.class);
        long orderId = System.nanoTime();
        jdbc.update("INSERT INTO shop_ratings (shop_id, customer_id, order_id, rating, created_at) "
                + "VALUES (?, ?, ?, ?, now())", shopId, customerId, orderId, stars);
    }

    private String discovery(String extra) throws Exception {
        return mockMvc.perform(get("/api/marketplace/discovery?lat=" + LAT + "&lng=" + LNG + extra))
                .andReturn().getResponse().getContentAsString();
    }

    /** Puts one catalogue item on one shop's shelf, priced. */
    private void list(long shopId, Long variantId) {
        jdbc.update("INSERT INTO shop_product_variants "
                        + "(shop_id, product_variant_id, selling_price, available, active, commerce_mode) "
                        + "VALUES (?, ?, ?, true, true, 'ONLINE_PURCHASE')",
                shopId, variantId, new BigDecimal("99.00"));
    }

    private Long newMerchant(String kind) {
        Long id = merchantLifecycle.register(
                "Category Discovery " + kind + " " + tag, "Category Discovery Shop",
                null, null, null, true).getId();
        merchantLifecycle.transition(id, MerchantStatus.PENDING_REVIEW, "submitted");
        merchantLifecycle.transition(id, MerchantStatus.APPROVED, "checked");
        merchantLifecycle.transition(id, MerchantStatus.ACTIVE, "trading");
        return id;
    }

    private long newShop(Long merchant, String code, double lat, double lng, String radiusKm) {
        long id = shopLifecycle.open(merchant, code, "Category Discovery Shop",
                lat, lng, new BigDecimal(radiusKm), "Asia/Kolkata").getId();
        shopLifecycle.transitionAsPlatform(id, ShopStatus.ACTIVE, "ready to trade");
        return id;
    }
}
