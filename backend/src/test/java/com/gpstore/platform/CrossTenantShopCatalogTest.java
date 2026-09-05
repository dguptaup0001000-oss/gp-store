package com.gpstore.platform;

import com.gpstore.catalog.shop.ShopCatalog;
import com.gpstore.catalog.shop.ShopProductVariant;
import com.gpstore.catalog.shop.ShopProductVariantRepository;
import com.gpstore.entity.Category;
import com.gpstore.entity.DeliveryPricingSettings;
import com.gpstore.entity.Product;
import com.gpstore.entity.ProductVariant;
import com.gpstore.entity.StoreOperationsSettings;
import com.gpstore.store.StoreOrderAcceptance;
import com.gpstore.repository.CategoryRepository;
import com.gpstore.repository.DeliveryPricingSettingsRepository;
import com.gpstore.repository.InventoryRepository;
import com.gpstore.repository.ProductRepository;
import com.gpstore.repository.ProductVariantRepository;
import com.gpstore.repository.StoreOperationsSettingsRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * One catalogue, many shops, and no shop can touch another's commercial terms.
 *
 * THE ARCHITECTURE THIS PROVES. products and product_variants hold ONE row per
 * real-world item, shared by every shop that sells it. What a shop charges,
 * whether it lists the item and how many it has are separate rows keyed by
 * (shop, variant). The catalogue is never copied per shop, and there is no
 * per-shop code anywhere: Shop A, Shop B and the third shop created halfway
 * down this file all go through the same methods.
 *
 * WHY THAT MATTERS MORE THAN THE ORDER TESTS. An order is obviously private. A
 * price looks like public information right up until you notice that a
 * competitor's cost price, margin and stock levels are the commercially
 * sensitive part of running a kirana - and that they all live in these rows.
 */
@SpringBootTest(properties = {
        "outbox.initial-delay-ms=3600000",
        "outbox.drain-interval-ms=3600000",
        "payment.expiry-initial-delay-ms=3600000",
        "idempotency.cleanup-initial-delay-ms=3600000",
        "otp.cleanup-initial-delay-ms=3600000",
        "delivery.late-flag-initial-delay-ms=3600000"
})
@DisplayName("One central catalogue, per-shop commercial terms, no crossing over")
class CrossTenantShopCatalogTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private ShopRepository shops;
    @Autowired private MerchantRepository merchants;
    @Autowired private PlatformProperties platform;
    @Autowired private ShopCatalog shopCatalog;
    @Autowired private ShopProductVariantRepository listings;
    @Autowired private ProductRepository products;
    @Autowired private ProductVariantRepository variants;
    @Autowired private CategoryRepository categories;
    @Autowired private InventoryRepository inventory;
    @Autowired private StoreOperationsSettingsRepository storeSettings;
    @Autowired private DeliveryPricingSettingsRepository pricingSettings;

    private final String tag = "cat" + System.nanoTime();

    private long shopA;
    private long shopB;
    private Long merchantB;
    private Long categoryId;
    private Long productId;
    private Long variantId;

    private static final BigDecimal SHOP_A_PRICE = new BigDecimal("52.00");
    private static final BigDecimal SHOP_B_PRICE = new BigDecimal("47.50");

    @BeforeEach
    void oneProductSoldByTwoShops() {
        shopA = shops.findByCode(platform.getFirstShopCode()).orElseThrow().getId();
        shopB = newShop("CAT-" + tag);

        Category category = new Category();
        category.setName("Catalogue tenancy " + tag);
        category.setActive(true);
        category.setGstRate(new BigDecimal("5"));
        categoryId = categories.save(category).getId();

        Product product = new Product();
        product.setName("Shared atta " + tag);
        product.setCategory(category);
        product.setActive(true);
        productId = products.save(product).getId();

        // ONE catalogue row. Both shops below point at this same variant -
        // nothing about adding a second shop duplicates it.
        ProductVariant variant = new ProductVariant();
        variant.setProduct(product);
        variant.setQuantity(5.0);
        variant.setUnit("kg");
        variant.setSellingPrice(new BigDecimal("50.00"));
        variant.setCostPrice(new BigDecimal("40.00"));
        variant.setAvailable(Boolean.TRUE);
        variant.setActive(Boolean.TRUE);
        variantId = variants.save(variant).getId();

        listAt(shopA, SHOP_A_PRICE, new BigDecimal("41.00"));
        listAt(shopB, SHOP_B_PRICE, new BigDecimal("39.00"));
    }

    @AfterEach
    void removeTheFixture() {
        jdbc.update("DELETE FROM shop_product_variants WHERE product_variant_id = ?", variantId);
        jdbc.update("DELETE FROM inventory WHERE product_variant_id = ?", variantId);
        jdbc.update("DELETE FROM product_variants WHERE id = ?", variantId);
        jdbc.update("DELETE FROM products WHERE id = ?", productId);
        jdbc.update("DELETE FROM categories WHERE id = ?", categoryId);
        jdbc.update("DELETE FROM store_operations_settings WHERE shop_id <> ?", shopA);
        jdbc.update("DELETE FROM delivery_pricing_settings WHERE shop_id <> ?", shopA);
        jdbc.update("DELETE FROM shop_product_variants WHERE shop_id <> ?", shopA);
        jdbc.update("DELETE FROM shops WHERE code like ?", "CAT-" + tag + "%");
        jdbc.update("DELETE FROM merchants WHERE legal_name like ?", "Catalogue fixture " + tag + "%");
    }

    // -------------------------------------------------------- A. positive

    @Test
    @DisplayName("the catalogue row is shared; only the price is per shop")
    void oneCatalogueRowTwoPrices() {
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM product_variants WHERE id = ?", Integer.class, variantId),
                "the central catalogue must hold exactly one row for an item two shops sell");
        assertEquals(2, jdbc.queryForObject(
                "SELECT count(*) FROM shop_product_variants WHERE product_variant_id = ?",
                Integer.class, variantId),
                "each shop must have its own commercial terms for that one row");

        assertEquals(0, SHOP_A_PRICE.compareTo(priceIn(shopA)), "Shop A must see its own price");
        assertEquals(0, SHOP_B_PRICE.compareTo(priceIn(shopB)), "Shop B must see its own price");
    }

    @Test
    @DisplayName("a third shop needs rows, not code")
    void shopNAddsNoCode() {
        long shopC = newShop("CAT-" + tag + "-C");
        BigDecimal shopCPrice = new BigDecimal("55.25");

        // Exactly the same call as the other two shops. Nothing branches on
        // which shop this is, and nothing had to be written for it to exist.
        listAt(shopC, shopCPrice, new BigDecimal("42.00"));

        assertEquals(0, shopCPrice.compareTo(priceIn(shopC)));
        assertEquals(0, SHOP_A_PRICE.compareTo(priceIn(shopA)), "the new shop changed nothing for A");
        assertEquals(0, SHOP_B_PRICE.compareTo(priceIn(shopB)), "or for B");
    }

    // -------------------------------------------------------- B. negative

    @Test
    @DisplayName("a shop's listing lookup returns its own row and never the other's")
    void listingLookupIsScoped() {
        ShopProductVariant fromA = TenantContext.runWithin(TenantScope.ofShop(shopA),
                () -> shopCatalog.listingFor(variantId)).orElseThrow();
        ShopProductVariant fromB = TenantContext.runWithin(TenantScope.ofShop(shopB),
                () -> shopCatalog.listingFor(variantId)).orElseThrow();

        assertEquals(shopA, fromA.getShopId());
        assertEquals(shopB, fromB.getShopId());
        assertNotEquals(fromA.getId(), fromB.getId(), "the two shops must not share one row");
    }

    @Test
    @DisplayName("a shop cannot see another shop's cost price, which is its margin")
    void costPriceDoesNotCrossShops() {
        BigDecimal costSeenByA = TenantContext.runWithin(TenantScope.ofShop(shopA),
                () -> shopCatalog.listingFor(variantId)).orElseThrow().getCostPrice();

        assertEquals(0, new BigDecimal("41.00").compareTo(costSeenByA),
                "Shop A must see its own cost price");
        assertNotEquals(0, new BigDecimal("39.00").compareTo(costSeenByA),
                "Shop A is reading Shop B's wholesale cost - that is a competitor's margin");
    }

    // --------------------------------------------- C. direct ID manipulation

    @Test
    @DisplayName("another shop's listing id is refused, not returned")
    void listingIdManipulationIsRefused() {
        long listingB = listingIdFor(shopB);

        assertTrue(TenantContext.runWithin(TenantScope.ofShop(shopB),
                () -> listings.findById(listingB)).isPresent(), "Shop B must open its own listing");

        assertThrows(CrossShopAccessException.class,
                () -> TenantContext.runWithin(TenantScope.ofShop(shopA), () -> listings.findById(listingB)),
                "changing the id handed Shop A the price list of Shop B");
    }

    // ------------------------------------- D. unauthorised write and delete

    @Test
    @DisplayName("a shop cannot reprice another shop's listing")
    void repricingAnotherShopIsRefused() {
        long listingB = listingIdFor(shopB);

        assertThrows(CrossShopAccessException.class,
                () -> TenantContext.runWithin(TenantScope.ofShop(shopA), () -> {
                    ShopProductVariant stolen = listings.findById(listingB).orElseThrow();
                    stolen.setSellingPrice(new BigDecimal("1.00"));
                    return listings.save(stolen);
                }));

        assertEquals(0, SHOP_B_PRICE.compareTo(priceIn(shopB)),
                "Shop B's price changed despite the refusal - a competitor could undercut them "
                        + "by editing their price list");
    }

    @Test
    @DisplayName("a shop cannot delist another shop's item")
    void delistingAnotherShopIsRefused() {
        long listingB = listingIdFor(shopB);

        assertThrows(RuntimeException.class,
                () -> TenantContext.runWithin(TenantScope.ofShop(shopA), () -> {
                    listings.deleteById(listingB);
                    return null;
                }));

        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM shop_product_variants WHERE id = ?", Integer.class, listingB),
                "Shop A removed an item from Shop B's shelf");
    }

    @Test
    @DisplayName("delisting inside one shop leaves every other shop's shelf alone")
    void delistingIsLocalToTheShop() {
        TenantContext.runWithin(TenantScope.ofShop(shopA), () -> {
            shopCatalog.delist(variantId);
            return null;
        });

        assertFalse(TenantContext.runWithin(TenantScope.ofShop(shopA),
                () -> shopCatalog.listingFor(variantId)).orElseThrow().isOrderable(),
                "Shop A delisted it, so Shop A must not be selling it");
        assertTrue(TenantContext.runWithin(TenantScope.ofShop(shopB),
                () -> shopCatalog.listingFor(variantId)).orElseThrow().isOrderable(),
                "one shop dropping a line must not take it off the marketplace");
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM product_variants WHERE id = ? AND active = true",
                Integer.class, variantId),
                "delisting must not touch the shared catalogue row");
    }

    // -------------------------------------------- E. server-side stamping

    @Test
    @DisplayName("a shop id carried on a new listing is overwritten by the scope")
    void theShopOnAnIncomingListingIsIgnored() {
        // A second variant, so this test creates rather than updates.
        ProductVariant another = new ProductVariant();
        another.setProduct(products.findById(productId).orElseThrow());
        another.setQuantity(1.0);
        another.setUnit("kg");
        another.setSellingPrice(new BigDecimal("20.00"));
        another.setAvailable(Boolean.TRUE);
        another.setActive(Boolean.TRUE);
        Long secondVariantId = variants.save(another).getId();

        try {
            Long written = TenantContext.runWithin(TenantScope.ofShop(shopB), () -> {
                ShopProductVariant smuggled = new ShopProductVariant();
                smuggled.setProductVariantId(secondVariantId);
                smuggled.setSellingPrice(new BigDecimal("20.00"));
                smuggled.setAvailable(Boolean.TRUE);
                smuggled.setActive(Boolean.TRUE);
                // A request body naming somebody else's shop.
                smuggled.setShopId(shopA);
                return listings.save(smuggled).getId();
            });

            assertEquals(shopB, jdbc.queryForObject(
                    "SELECT shop_id FROM shop_product_variants WHERE id = ?", Long.class, written),
                    "a shop id on the object overrode the shop the credential resolved to");
        } finally {
            jdbc.update("DELETE FROM shop_product_variants WHERE product_variant_id = ?", secondVariantId);
            jdbc.update("DELETE FROM product_variants WHERE id = ?", secondVariantId);
        }
    }

    // ------------------------------------------------ F. platform exception

    @Test
    @DisplayName("platform-wide work sees every shop's terms, which is the point of the role")
    void platformScopeSeesEveryShop() {
        List<ShopProductVariant> all = TenantContext.runWithin(TenantScope.platform(),
                () -> listings.findByProductVariantIdIn(List.of(variantId)));

        assertEquals(2, all.size(),
                "a marketplace operator comparing prices across shops must be able to see them");
    }

    // ------------------------------------------------- stock, per shop now

    @Test
    @DisplayName("two shops can hold stock of the same item, and neither sees the other's")
    void stockIsPerShopPerVariant() {
        jdbc.update("INSERT INTO inventory (product_variant_id, stock, reserved_stock, shop_id) "
                + "VALUES (?, 7, 0, ?)", variantId, shopA);
        jdbc.update("INSERT INTO inventory (product_variant_id, stock, reserved_stock, shop_id) "
                + "VALUES (?, 9, 0, ?)", variantId, shopB);

        Integer seenByA = TenantContext.runWithin(TenantScope.ofShop(shopA),
                () -> inventory.findByProductVariantId(variantId).orElseThrow().getStock());
        Integer seenByB = TenantContext.runWithin(TenantScope.ofShop(shopB),
                () -> inventory.findByProductVariantId(variantId).orElseThrow().getStock());

        assertEquals(7, seenByA, "before V48 the unique key made a second shop's stock row "
                + "impossible to insert at all");
        assertEquals(9, seenByB);
    }

    @Test
    @DisplayName("the combined stock-and-price query is filtered on both halves of its join")
    void theShelfQueryIsFilteredOnBothEntities() {
        jdbc.update("INSERT INTO inventory (product_variant_id, stock, reserved_stock, shop_id) "
                + "VALUES (?, 7, 0, ?)", variantId, shopA);
        jdbc.update("INSERT INTO inventory (product_variant_id, stock, reserved_stock, shop_id) "
                + "VALUES (?, 9, 0, ?)", variantId, shopB);

        // The cart read joins Inventory to ShopProductVariant in ONE query.
        // Both are shop-owned, and a join is exactly where a filter is easy to
        // apply to one side and forget on the other - which would serve this
        // shop's stock at another shop's price.
        List<ShopProductVariantRepository.ShelfLine> linesForA =
                TenantContext.runWithin(TenantScope.ofShop(shopA),
                        () -> listings.findShelfLines(List.of(variantId)));

        assertEquals(1, linesForA.size(), "Shop A must see exactly its own stock row");
        assertEquals(7, linesForA.get(0).getStock());
        assertEquals(0, SHOP_A_PRICE.compareTo(linesForA.get(0).getPrice()),
                "the price on Shop A's shelf line came from another shop's listing");
    }

    // ---------------------------------------------- settings, per shop now

    @Test
    @DisplayName("store hours and order acceptance are per shop")
    void storeOperationsSettingsAreScoped() {
        StoreOperationsSettings forB = TenantContext.runWithin(TenantScope.ofShop(shopB), () -> {
            StoreOperationsSettings row = new StoreOperationsSettings();
            row.setOrderAcceptance(StoreOrderAcceptance.OFF);
            row.setClosureMessage("Shut for a wedding");
            return storeSettings.save(row);
        });

        assertEquals(shopB, forB.getShopId(), "the row must be stamped with the scope's shop");

        Optional<StoreOperationsSettings> seenByA = TenantContext.runWithin(TenantScope.ofShop(shopA),
                () -> storeSettings.findByShopId(shopB));
        assertTrue(seenByA.isEmpty(),
                "Shop A read Shop B's operations settings - one shop could see, and by the same "
                        + "route close, another shop's counter");
    }

    @Test
    @DisplayName("delivery pricing is per shop, and an id in a request body cannot cross shops")
    void deliveryPricingSettingsAreScoped() {
        DeliveryPricingSettings forB = TenantContext.runWithin(TenantScope.ofShop(shopB), () -> {
            DeliveryPricingSettings row = new DeliveryPricingSettings();
            row.normalise();
            return pricingSettings.save(row);
        });

        assertEquals(shopB, forB.getShopId());
        assertTrue(TenantContext.runWithin(TenantScope.ofShop(shopA),
                () -> pricingSettings.findByShopId(shopB)).isEmpty(),
                "Shop A read Shop B's delivery pricing");

        assertThrows(CrossShopAccessException.class,
                () -> TenantContext.runWithin(TenantScope.ofShop(shopA),
                        () -> pricingSettings.findById(forB.getId())),
                "the row that used to be a singleton is still reachable by id - and by id is "
                        + "exactly how a settings row would be rewritten from another shop");
    }

    // ------------------------------------------------------------ fixtures

    private long newShop(String code) {
        Merchant merchant = new Merchant();
        merchant.setLegalName("Catalogue fixture " + tag + " " + code);
        merchant.setDisplayName("Fixture");
        merchant.setStatus(MerchantStatus.ACTIVE);
        merchant.setIsDemo(Boolean.TRUE);
        merchant.setActive(Boolean.TRUE);
        merchantB = merchants.save(merchant).getId();

        Shop shop = new Shop();
        shop.setMerchantId(merchantB);
        shop.setCode(code);
        shop.setDisplayName("Fixture " + code);
        shop.setStatus(ShopStatus.ACTIVE);
        shop.setIsDemo(Boolean.TRUE);
        shop.setActive(Boolean.TRUE);
        return shops.save(shop).getId();
    }

    /** Lists the shared variant at one shop, through the ordinary path. */
    private void listAt(long shopId, BigDecimal price, BigDecimal cost) {
        TenantContext.runWithin(TenantScope.ofShop(shopId), () -> {
            ShopProductVariant listing = listings.findByProductVariantId(variantId)
                    .orElseGet(ShopProductVariant::new);
            listing.setProductVariantId(variantId);
            listing.setSellingPrice(price);
            listing.setCostPrice(cost);
            listing.setAvailable(Boolean.TRUE);
            listing.setActive(Boolean.TRUE);
            return listings.save(listing);
        });
    }

    private BigDecimal priceIn(long shopId) {
        return TenantContext.runWithin(TenantScope.ofShop(shopId),
                () -> shopCatalog.listingFor(variantId)).orElseThrow().getSellingPrice();
    }

    private long listingIdFor(long shopId) {
        return jdbc.queryForObject(
                "SELECT id FROM shop_product_variants WHERE shop_id = ? AND product_variant_id = ?",
                Long.class, shopId, variantId);
    }
}
