package com.gpstore.platform;

import com.gpstore.catalog.shop.CommerceMode;
import com.gpstore.platform.api.MarketplaceFeedService;
import com.gpstore.platform.api.MarketplaceFeedView;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A customer opens GP-STORE, has chosen nobody's shop, and sees the town.
 *
 * <h2>The screen this test is about</h2>
 *
 * <p>"All Products - No products available yet", on a phone standing between
 * five shops full of stock. The cause was never the empty-state text and never
 * the product query: every customer browse path was scoped to ONE shop, and a
 * customer who had chosen none fell through TenantResolver to Shop #1. So the
 * home screen showed Shop #1's shelf - a kirana's groceries where the first
 * shop is a kirana, and nothing at all where it has no listings.
 *
 * <p>This fixture is the situation from the report: a kirana, a phone shop, a
 * saree shop, a hardware shop and an electronics shop, all near the customer,
 * all with live listings, and a customer who has selected none of them.
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
@AutoConfigureMockMvc
@DisplayName("Open the app, choose no shop, see the marketplace")
class OpenTheAppAndSeeTheMarketplaceTest {

    @Autowired private MarketplaceFeedService feed;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ShopRepository shops;
    @Autowired private MerchantRepository merchants;
    @Autowired private PlatformProperties platform;
    @Autowired private MockMvc mockMvc;
    @Autowired private com.fasterxml.jackson.databind.ObjectMapper json;

    /** A pin the customer is standing on; every fixture shop is within a few hundred metres. */
    private static final double LAT = 19.4321;
    private static final double LNG = 74.5678;

    private final String tag = "feed" + System.nanoTime();
    private Long merchantId;
    private final List<Long> shopIds = new ArrayList<>();
    private final List<Long> productIds = new ArrayList<>();
    private final List<Long> categoryIds = new ArrayList<>();

    @BeforeEach
    void fiveShopsAndACustomerWhoHasChosenNone() {
        TenantDefaults.install(PlatformMode.MULTI_SHOP_PRODUCTION,
                () -> shops.findByCode(platform.getFirstShopCode()).orElseThrow().getId());

        Merchant m = new Merchant();
        m.setLegalName("Feed fixture " + tag);
        m.setDisplayName("Feed fixture");
        m.setStatus(MerchantStatus.ACTIVE);
        m.setIsDemo(Boolean.TRUE);
        m.setActive(Boolean.TRUE);
        merchantId = merchants.save(m).getId();

        // Five different trades, so "mixed" means something a human would
        // recognise as mixed rather than five flavours of biscuit.
        stock("Kirana", "Atta 5kg", "Grocery", CommerceMode.ONLINE_PURCHASE, "250");
        stock("Phone shop", "Phone charger", "Mobiles", CommerceMode.ONLINE_PURCHASE, "499");
        stock("Saree shop", "Cotton saree", "Fashion", CommerceMode.ONLINE_PURCHASE, "1200");
        stock("Hardware", "Hammer", "Hardware", CommerceMode.ONLINE_PURCHASE, "350");
        stock("Electronics", "LED bulb", "Electronics", CommerceMode.ONLINE_PURCHASE, "120");
    }

    @AfterEach
    void tidyUp() {
        TenantContext.clear();
        for (Long shopId : shopIds) {
            jdbc.update("DELETE FROM shop_product_variants WHERE shop_id = ?", shopId);
            jdbc.update("DELETE FROM shop_business_hours WHERE shop_id = ?", shopId);
            jdbc.update("DELETE FROM delivery_pricing_settings WHERE shop_id = ?", shopId);
            jdbc.update("DELETE FROM store_operations_settings WHERE shop_id = ?", shopId);
            jdbc.update("DELETE FROM shops WHERE id = ?", shopId);
        }
        for (Long productId : productIds) {
            jdbc.update("DELETE FROM product_images WHERE product_id = ?", productId);
            jdbc.update("DELETE FROM product_variants WHERE product_id = ?", productId);
            jdbc.update("DELETE FROM products WHERE id = ?", productId);
        }
        for (Long categoryId : categoryIds) {
            jdbc.update("DELETE FROM categories WHERE id = ?", categoryId);
        }
        jdbc.update("DELETE FROM merchants WHERE id = ?", merchantId);
        TenantDefaults.install(platform.getMode(),
                () -> shops.findByCode(platform.getFirstShopCode()).orElseThrow().getId());
    }

    @Nested
    @DisplayName("The reported screen")
    class TheReportedScreen {

        @Test
        @DisplayName("shows products from every nearby shop, with no shop chosen")
        void aMixedFeedWithoutChoosingAShop() {
            List<MarketplaceFeedView> cards = feed.page(LAT, LNG,
                    Set.of(CommerceMode.ONLINE_PURCHASE), null, 0, 20);

            assertFalse(cards.isEmpty(),
                    "THE REPORTED BUG. Five shops with live listings stand around this pin and "
                            + "the customer has chosen none of them. An empty answer here is the "
                            + "'No products available yet' screen.");

            Set<Long> shopsRepresented = new HashSet<>();
            for (MarketplaceFeedView card : cards) {
                shopsRepresented.add(card.shopId());
            }
            assertTrue(shopsRepresented.size() >= 5,
                    "the feed must span the marketplace, not one shop's shelf. Shops seen: "
                            + shopsRepresented.size());
        }

        @Test
        @DisplayName("the actual Customer APK route returns parseable cross-shop JSON")
        void customerHomeWireContract() throws Exception {
            String body = mockMvc.perform(get("/api/marketplace/feed")
                            .param("lat", String.valueOf(LAT))
                            .param("lng", String.valueOf(LNG))
                            .param("mode", "ONLINE_PURCHASE")
                            .param("page", "0")
                            .param("size", "20"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            var cards = json.readTree(body);
            assertTrue(cards.isArray() && cards.size() >= 5,
                    "the serialized HTTP response was not the five-shop feed: " + body);
            Set<Long> represented = new HashSet<>();
            boolean phone = false;
            for (var card : cards) {
                represented.add(card.path("shopId").asLong());
                assertEquals("ONLINE_PURCHASE", card.path("commerceMode").asText());
                assertTrue(card.path("addable").asBoolean(),
                        "Buy Online card was serialized as non-addable: " + card);
                phone |= "Phone charger".equals(card.path("name").asText());
            }
            assertTrue(phone, "the non-Shop-#1 phone listing vanished from HTTP JSON: " + body);
            assertTrue(represented.size() >= 5,
                    "the HTTP endpoint collapsed back to one shop: " + represented);
        }

        @Test
        @DisplayName("the real HTTP JSON keeps every mode and applies a selected shop")
        void customerFeedWireContract() throws Exception {
            Long visitVariant = variantOf(productIds.get(1));
            jdbc.update("UPDATE shop_product_variants SET commerce_mode = 'VISIT_TO_BUY', "
                            + "price_mode = 'STARTING_FROM' WHERE shop_id = ? AND product_variant_id = ?",
                    shopIds.get(1), visitVariant);
            Long serviceVariant = variantOf(productIds.get(2));
            jdbc.update("UPDATE shop_product_variants SET commerce_mode = 'SERVICE_AT_SHOP', "
                            + "price_mode = 'STARTING_FROM' WHERE shop_id = ? AND product_variant_id = ?",
                    shopIds.get(2), serviceVariant);

            String body = mockMvc.perform(get("/api/marketplace/feed")
                            .param("lat", String.valueOf(LAT))
                            .param("lng", String.valueOf(LNG))
                    .param("page", "0").param("size", "20"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            var cards = json.readTree(body);
            assertTrue(cards.isArray() && cards.size() >= 5,
                    "the serialized marketplace response did not include the nearby fixture: " + body);
            Set<Long> represented = new HashSet<>();
            boolean online = false, visit = false, service = false;
            for (var card : cards) {
                represented.add(card.path("shopId").asLong());
                String mode = card.path("commerceMode").asText();
                assertTrue(Set.of("ONLINE_PURCHASE", "VISIT_TO_BUY", "SERVICE_AT_SHOP").contains(mode),
                        "the HTTP contract omitted/changed commerceMode: " + card);
                if ("ONLINE_PURCHASE".equals(mode)) {
                    online = true;
                    assertTrue(card.path("addable").asBoolean(), "Buy Online should be addable: " + card);
                } else {
                    assertFalse(card.path("addable").asBoolean(),
                            "offline modes must not be cartable: " + card);
                }
                visit |= "VISIT_TO_BUY".equals(mode);
                service |= "SERVICE_AT_SHOP".equals(mode);
            }
            assertTrue(online && visit && service,
                    "the three modes were not all serialized: " + body);
            assertTrue(represented.size() >= 5,
                    "ALL nearby mode did not span multiple shops: " + represented);

            String filtered = mockMvc.perform(get("/api/marketplace/feed")
                            .param("lat", String.valueOf(LAT)).param("lng", String.valueOf(LNG))
                            .param("shopId", String.valueOf(shopIds.get(1)))
                            .param("page", "0").param("size", "20"))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            for (var card : json.readTree(filtered)) {
                assertEquals(shopIds.get(1).longValue(), card.path("shopId").asLong(),
                        "selected shop filter leaked another shop's listing: " + filtered);
            }
        }

        @Test
        @DisplayName("an unsupported commerce mode is refused rather than guessed as Buy Online")
        void unknownModeIsBadRequest() throws Exception {
            mockMvc.perform(get("/api/marketplace/feed")
                            .param("lat", String.valueOf(LAT))
                            .param("lng", String.valueOf(LNG))
                            .param("mode", "BUY_ONLINE"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("is not one trade's shelf wearing a marketplace's name")
        void theFeedIsGenuinelyMixed() {
            List<MarketplaceFeedView> cards = feed.page(LAT, LNG,
                    Set.of(CommerceMode.ONLINE_PURCHASE), null, 0, 20);

            Set<String> categories = new HashSet<>();
            for (MarketplaceFeedView card : cards) {
                if (card.categoryName() != null) {
                    categories.add(card.categoryName());
                }
            }
            assertTrue(categories.size() >= 5,
                    "a home screen showing only groceries is the old Shop #1 behaviour with a "
                            + "new endpoint in front of it. Categories seen: " + categories);
        }

        @Test
        @DisplayName("a customer nowhere near any shop is told nothing, not shown everything")
        void noPinMeansNoMarketplace() {
            assertTrue(feed.page(null, null, Set.of(CommerceMode.ONLINE_PURCHASE), null, 0, 20)
                            .isEmpty(),
                    "inventing a location would show a customer in one town another town's shops");
            assertTrue(feed.page(1.0, 1.0, Set.of(CommerceMode.ONLINE_PURCHASE), null, 0, 20)
                            .isEmpty(),
                    "a pin in the Gulf of Guinea has no local marketplace, and saying so is "
                            + "the honest answer");
        }
    }

    @Nested
    @DisplayName("One card per product")
    class OneCardPerProduct {

        @Test
        @DisplayName("five shops selling the same thing is one card, not five")
        void theSameProductIsNotRepeated() {
            // The central catalogue already knows these are the same thing.
            // Drawing the listings instead of the product is how a marketplace
            // with a thousand shops manages to look emptier than one with ten.
            Long shared = productIds.get(0);
            for (int i = 1; i < shopIds.size(); i++) {
                listOn(shopIds.get(i), variantOf(shared), CommerceMode.ONLINE_PURCHASE,
                        new BigDecimal("240"));
            }

            List<MarketplaceFeedView> cards = feed.page(LAT, LNG,
                    Set.of(CommerceMode.ONLINE_PURCHASE), null, 0, 50);

            long timesShown = cards.stream().filter(c -> shared.equals(c.productId())).count();
            assertEquals(1L, timesShown,
                    "the same central product listed by five shops must be one card");

            MarketplaceFeedView card = cards.stream()
                    .filter(c -> shared.equals(c.productId())).findFirst().orElseThrow();
            assertTrue(card.sellerCount() >= 5,
                    "the card should be able to say how many shops have it, got "
                            + card.sellerCount());
        }
    }

    @Nested
    @DisplayName("Modes")
    class Modes {

        @Test
        @DisplayName("the marketplace card carries the stored product image across HTTP JSON")
        void feedImageAndSelectedShopSurviveTheWire() throws Exception {
            stock("Image shop", "Feed image product", "Grocery", CommerceMode.ONLINE_PURCHASE, "90");
            Long productId = productIds.get(productIds.size() - 1);
            Long shopId = shopIds.get(shopIds.size() - 1);
            jdbc.update("INSERT INTO product_images (product_id, image_url, sort_order, created_at) "
                            + "VALUES (?, ?, 0, now())",
                    productId, "https://images.example.test/feed-product.jpg");

            String body = mockMvc.perform(get("/api/marketplace/feed")
                            .param("lat", String.valueOf(LAT))
                            .param("lng", String.valueOf(LNG))
                            .param("mode", "ONLINE_PURCHASE")
                            .param("shopId", String.valueOf(shopId)))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            var cards = json.readTree(body);
            var card = java.util.stream.StreamSupport.stream(cards.spliterator(), false)
                    .filter(value -> value.path("productId").asLong() == productId)
                    .findFirst().orElseThrow();
            assertEquals("https://images.example.test/feed-product.jpg",
                    card.path("imageUrl").asText());
            assertEquals(shopId, card.path("shopId").asLong());
            assertTrue(card.path("inStock").asBoolean());
            assertTrue(card.path("addable").asBoolean());

            // Product detail is a shop-scoped API. A marketplace card must
            // carry the seller's shop through the detail request; without
            // this header an address/default shop may not list this product.
            mockMvc.perform(get("/api/products/" + productId)
                            .header("X-Shop-Id", shopId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("a listed online item with no stock stays visible but is not addable")
        void soldOutListingDoesNotOfferCartPurchase() throws Exception {
            stock("Sold out shop", "Sold out fixture", "Grocery", CommerceMode.ONLINE_PURCHASE, "90");
            Long productId = productIds.get(productIds.size() - 1);
            Long shopId = shopIds.get(shopIds.size() - 1);
            Long variantId = variantOf(productId);
            jdbc.update("UPDATE inventory SET stock = 2, reserved_stock = 2 "
                            + "WHERE shop_id = ? AND product_variant_id = ?",
                    shopId, variantId);

            String body = mockMvc.perform(get("/api/marketplace/feed")
                            .param("lat", String.valueOf(LAT))
                            .param("lng", String.valueOf(LNG))
                            .param("mode", "ONLINE_PURCHASE")
                            .param("shopId", String.valueOf(shopId)))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            var cards = json.readTree(body);
            var card = java.util.stream.StreamSupport.stream(cards.spliterator(), false)
                    .filter(value -> value.path("productId").asLong() == productId)
                    .findFirst().orElseThrow();
            assertFalse(card.path("inStock").asBoolean());
            assertFalse(card.path("addable").asBoolean());
        }

        @Test
        @DisplayName("omitting the mode filter includes all three commerce modes")
        void unfilteredHomeContainsEveryMode() {
            stock("Visit shop", "Visit fixture", "Grocery", CommerceMode.VISIT_TO_BUY, "90");
            stock("Service shop", "Service fixture", "Grocery", CommerceMode.SERVICE_AT_SHOP, "90");
            String body = mockMvc.perform(get("/api/marketplace/feed")
                            .param("lat", String.valueOf(LAT))
                            .param("lng", String.valueOf(LNG)))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            var all = json.readTree(body);
            Set<String> modes = new HashSet<>();
            for (var card : all) modes.add(card.path("commerceMode").asText());
            assertTrue(modes.contains(CommerceMode.ONLINE_PURCHASE.name()));
            assertTrue(modes.contains(CommerceMode.VISIT_TO_BUY.name()));
            assertTrue(modes.contains(CommerceMode.SERVICE_AT_SHOP.name()));
        }

        @Test
        @DisplayName("a Visit-to-Buy listing never appears in the Buy Online feed")
        void modesDoNotLeakIntoEachOther() {
            stock("Jeweller", "Gold ring", "Jewellery", CommerceMode.VISIT_TO_BUY, "75000");

            List<MarketplaceFeedView> online = feed.page(LAT, LNG,
                    Set.of(CommerceMode.ONLINE_PURCHASE), null, 0, 50);
            assertTrue(online.stream().noneMatch(c -> "Gold ring".equals(c.name())),
                    "a ring that must be bought at the shop appearing on a screen that draws "
                            + "ADD buttons is the whole failure this feature exists to prevent");

            List<MarketplaceFeedView> visit = feed.page(LAT, LNG,
                    Set.of(CommerceMode.VISIT_TO_BUY), null, 0, 50);
            assertTrue(visit.stream().anyMatch(c -> "Gold ring".equals(c.name())),
                    "and it must be discoverable in the mode it belongs to");
            assertTrue(visit.stream().filter(c -> "Gold ring".equals(c.name()))
                            .noneMatch(MarketplaceFeedView::addable),
                    "every Visit-to-Buy card must tell the client it is not addable, so no "
                            + "client has to work that out for itself");
        }

        @Test
        @DisplayName("one shop can trade in more than one mode at once")
        void aShopIsNotClassifiedAsAWhole() {
            // A phone shop sells a charger online, asks you to come and look
            // at the handset, and repairs a screen on the bench.
            Long phoneShop = shopIds.get(1);
            Long handset = newProduct("Moto Edge 50", "Mobiles");
            Long repair = newProduct("Screen replacement", "Mobiles");
            listOn(phoneShop, variantOf(handset), CommerceMode.VISIT_TO_BUY, new BigDecimal("24999"));
            listOn(phoneShop, variantOf(repair), CommerceMode.SERVICE_AT_SHOP, new BigDecimal("2500"));

            assertTrue(feed.page(LAT, LNG, Set.of(CommerceMode.ONLINE_PURCHASE), null, 0, 50)
                            .stream().anyMatch(c -> "Phone charger".equals(c.name())),
                    "the charger is still sold online");
            assertTrue(feed.page(LAT, LNG, Set.of(CommerceMode.VISIT_TO_BUY), null, 0, 50)
                            .stream().anyMatch(c -> "Moto Edge 50".equals(c.name())),
                    "the handset is discoverable as Visit to Buy");
            assertTrue(feed.page(LAT, LNG, Set.of(CommerceMode.SERVICE_AT_SHOP), null, 0, 50)
                            .stream().anyMatch(c -> "Screen replacement".equals(c.name())),
                    "the repair is discoverable as a service");
        }
    }

    @Nested
    @DisplayName("Paging")
    class Paging {

        @Test
        @DisplayName("pages do not overlap and do not skip")
        void pagingIsStable() {
            List<MarketplaceFeedView> first = feed.page(LAT, LNG,
                    Set.of(CommerceMode.ONLINE_PURCHASE), null, 0, 2);
            List<MarketplaceFeedView> second = feed.page(LAT, LNG,
                    Set.of(CommerceMode.ONLINE_PURCHASE), null, 1, 2);

            assertEquals(2, first.size(), "a full page should be full");
            Set<Long> firstIds = new HashSet<>();
            first.forEach(c -> firstIds.add(c.productId()));
            for (MarketplaceFeedView card : second) {
                assertFalse(firstIds.contains(card.productId()),
                        "infinite scroll re-queries by offset; a product appearing on two "
                                + "consecutive pages is a duplicate on the customer's screen");
            }
        }

        @Test
        @DisplayName("a secondary-shop product survives ALL-feed offset pagination over real HTTP JSON")
        void secondaryShopProductAppearsOnLaterAllPage() throws Exception {
            Long firstShopId = shopIds.get(0);
            for (int i = 0; i < 50; i++) {
                Long filler = newProduct("Pagination filler " + i, "Grocery");
                listOn(firstShopId, variantOf(filler), CommerceMode.ONLINE_PURCHASE,
                        new BigDecimal("50"));
            }

            Long secondaryShopId = newShop("Page2");
            Long secondaryProductId = newProduct("Secondary page product", "Hardware");
            listOn(secondaryShopId, variantOf(secondaryProductId),
                    CommerceMode.ONLINE_PURCHASE, new BigDecimal("75"));

            String firstBody = mockMvc.perform(get("/api/marketplace/feed")
                            .param("lat", String.valueOf(LAT))
                            .param("lng", String.valueOf(LNG))
                            .param("mode", "ONLINE_PURCHASE")
                            .param("page", "0")
                            .param("size", "50"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            var firstPage = json.readTree(firstBody);
            assertEquals(50, firstPage.size(), "the first page should be filled by nearer/equal fixtures");
            assertFalse(java.util.stream.StreamSupport.stream(firstPage.spliterator(), false)
                            .anyMatch(card -> card.path("productId").asLong() == secondaryProductId),
                    "the last inserted product should require the next page in this stable fixture");

            String nextBody = mockMvc.perform(get("/api/marketplace/feed")
                            .param("lat", String.valueOf(LAT))
                            .param("lng", String.valueOf(LNG))
                            .param("mode", "ONLINE_PURCHASE")
                            .param("page", "1")
                            .param("size", "50"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            var nextPage = json.readTree(nextBody);
            assertTrue(java.util.stream.StreamSupport.stream(nextPage.spliterator(), false)
                            .anyMatch(card -> card.path("productId").asLong() == secondaryProductId),
                    "the combined ALL feed must retain the eligible secondary-shop item after offset paging: "
                            + nextBody);
            for (var card : nextPage) {
                assertEquals("ONLINE_PURCHASE", card.path("commerceMode").asText(),
                        "the serialized page changed the listing's commerce mode: " + card);
            }

            String selectedBody = mockMvc.perform(get("/api/marketplace/feed")
                            .param("lat", String.valueOf(LAT))
                            .param("lng", String.valueOf(LNG))
                            .param("mode", "ONLINE_PURCHASE")
                            .param("shopId", String.valueOf(secondaryShopId))
                            .param("page", "0")
                            .param("size", "50"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            var selectedCards = json.readTree(selectedBody);
            assertTrue(java.util.stream.StreamSupport.stream(selectedCards.spliterator(), false)
                            .anyMatch(card -> card.path("productId").asLong() == secondaryProductId),
                    "the selected-shop HTTP response should retain its eligible product: " + selectedBody);
        }

        @Test
        @DisplayName("a caller cannot ask for the whole marketplace in one page")
        void pageSizeIsBounded() {
            assertTrue(feed.page(LAT, LNG, Set.of(CommerceMode.ONLINE_PURCHASE), null, 0, 100_000)
                            .size() <= 50,
                    "an unbounded page size turns a feed endpoint into a catalogue dump");
        }
    }

    // ------------------------------------------------------------- fixture

    private void stock(String shopName, String productName, String categoryName,
                       CommerceMode mode, String price) {
        Long shopId = newShop(shopName);
        Long productId = newProduct(productName, categoryName);
        listOn(shopId, variantOf(productId), mode, new BigDecimal(price));
    }

    private Long newShop(String name) {
        Shop shop = new Shop();
        shop.setMerchantId(merchantId);
        shop.setCode((name + "-" + tag).toLowerCase(java.util.Locale.ROOT).replace(' ', '-'));
        shop.setDisplayName(name + " " + tag);
        shop.setStatus(ShopStatus.ACTIVE);
        shop.setLatitude(LAT + 0.0009);
        shop.setLongitude(LNG);
        shop.setMaxDeliveryRadiusKm(new BigDecimal("5"));
        shop.setTimeZone("Asia/Kolkata");
        shop.setIsDemo(Boolean.TRUE);
        shop.setActive(Boolean.TRUE);
        Long id = shops.save(shop).getId();
        shopIds.add(id);
        return id;
    }

    private Long newProduct(String name, String categoryName) {
        Long categoryId = jdbc.query(
                "SELECT id FROM categories WHERE name = ? LIMIT 1",
                rs -> rs.next() ? rs.getLong(1) : null, categoryName + " " + tag);
        if (categoryId == null) {
            jdbc.update("INSERT INTO categories (name, active) VALUES (?, true)",
                    categoryName + " " + tag);
            categoryId = jdbc.queryForObject("SELECT id FROM categories WHERE name = ?",
                    Long.class, categoryName + " " + tag);
            categoryIds.add(categoryId);
        }
        jdbc.update("INSERT INTO products (name, brand, active, category_id) VALUES (?, ?, true, ?)",
                name, "TestBrand", categoryId);
        Long productId = jdbc.queryForObject(
                "SELECT id FROM products WHERE name = ? ORDER BY id DESC LIMIT 1",
                Long.class, name);
        productIds.add(productId);
        jdbc.update("INSERT INTO product_variants "
                + "(product_id, quantity, unit, mrp, selling_price, available, active) "
                + "VALUES (?, 1, 'pc', 100, 90, true, true)", productId);
        return productId;
    }

    private Long variantOf(Long productId) {
        return jdbc.queryForObject(
                "SELECT id FROM product_variants WHERE product_id = ? ORDER BY id LIMIT 1",
                Long.class, productId);
    }

    private void listOn(Long shopId, Long variantId, CommerceMode mode, BigDecimal price) {
        jdbc.update("INSERT INTO shop_product_variants "
                        + "(shop_id, product_variant_id, selling_price, mrp, available, active, "
                        + " commerce_mode, price_mode, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, true, true, ?, ?, now(), now())",
                shopId, variantId, price, price,
                mode.name(),
                mode.isBuyableOnline() ? "EXACT_PRICE" : "STARTING_FROM");
        jdbc.update("INSERT INTO inventory (shop_id, product_variant_id, stock, reserved_stock) "
                        + "VALUES (?, ?, 25, 0) ON CONFLICT (shop_id, product_variant_id) "
                        + "DO UPDATE SET stock = 25, reserved_stock = 0",
                shopId, variantId);
    }
}
