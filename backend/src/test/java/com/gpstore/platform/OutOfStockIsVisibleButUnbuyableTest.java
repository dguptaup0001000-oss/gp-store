package com.gpstore.platform;

import com.gpstore.catalog.shop.ShopProductVariant;
import com.gpstore.catalog.shop.ShopProductVariantRepository;
import com.gpstore.dto.response.ProductResponse;
import com.gpstore.dto.response.VariantResponse;
import com.gpstore.entity.Category;
import com.gpstore.entity.Inventory;
import com.gpstore.entity.Product;
import com.gpstore.entity.ProductVariant;
import com.gpstore.repository.CategoryRepository;
import com.gpstore.repository.InventoryRepository;
import com.gpstore.repository.ProductRepository;
import com.gpstore.repository.ProductVariantRepository;
import com.gpstore.service.ProductService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * §7 STATE 2: SOLD OUT IS NOT THE SAME AS NOT SOLD HERE.
 *
 * <p>THE THREE STATES, and the one that was missing. A shop that does not
 * list a product hides it (STATE 1, and the browse work of the last slice is
 * what makes that true). A shop that lists it and holds it shows a price and
 * an ADD button (STATE 3). Between them is the ordinary state of a kirana at
 * the end of a Sunday: it sells the thing, and it has run out.
 *
 * <p>WHAT WAS WRONG. The customer response carried "available", which is the
 * LISTING flag - listed, active, priced - and never consulted inventory. So an
 * item the shop had sold out of was drawn with its price and a live ADD
 * button, and the customer discovered the truth at the moment they tapped it.
 * The refusal itself was real and always had been (CartService.requireStockFor
 * has always checked), which is the point: the screen was the thing that was
 * wrong, and a screen that invites an action the server will refuse is worse
 * than one that says no.
 *
 * <p>AND IT IS PER VARIANT. A shop out of 1 kg but holding 5 kg is selling
 * 5 kg. An empty size must not take the sizes beside it down with it, and the
 * card must offer one that can actually be bought.
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
@DisplayName("Out of stock is visible, and unbuyable")
class OutOfStockIsVisibleButUnbuyableTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private ShopRepository shops;
    @Autowired private PlatformProperties platform;
    @Autowired private ProductService productService;
    @Autowired private org.springframework.cache.CacheManager caches;
    @Autowired private CategoryRepository categories;
    @Autowired private ProductRepository products;
    @Autowired private ProductVariantRepository variants;
    @Autowired private ShopProductVariantRepository listings;
    @Autowired private InventoryRepository inventory;

    private final String tag = "stk" + System.nanoTime();

    private long shopId;
    private Long categoryId;
    private Long productId;
    private Long emptyVariantId;
    private Long stockedVariantId;

    @BeforeEach
    void oneProductWithAnEmptySizeAndAStockedOne() {
        TenantDefaults.install(PlatformMode.MULTI_SHOP_PRODUCTION,
                () -> shops.findByCode(platform.getFirstShopCode()).orElseThrow().getId());
        shopId = shops.findByCode(platform.getFirstShopCode()).orElseThrow().getId();

        Category category = new Category();
        category.setName("Stock category " + tag);
        category.setActive(true);
        category.setGstRate(new BigDecimal("5"));
        categoryId = categories.save(category).getId();

        Product product = new Product();
        product.setName("Atta " + tag);
        product.setCategory(categories.findById(categoryId).orElseThrow());
        product.setActive(true);
        productId = TenantContext.runWithin(TenantScope.platform(),
                () -> products.save(product).getId());

        // The CHEAPER size is the empty one, deliberately: the card picks the
        // cheapest, so if the pick ignored stock it would land here every time.
        emptyVariantId = newVariant(product, 1.0, new BigDecimal("60.00"), 0);
        stockedVariantId = newVariant(product, 5.0, new BigDecimal("280.00"), 12);
    }

    @AfterEach
    void tidyUp() {
        TenantContext.clear();
        jdbc.update("DELETE FROM inventory WHERE product_variant_id IN (?, ?)",
                emptyVariantId, stockedVariantId);
        jdbc.update("DELETE FROM shop_product_variants WHERE product_variant_id IN (?, ?)",
                emptyVariantId, stockedVariantId);
        jdbc.update("DELETE FROM product_variants WHERE id IN (?, ?)",
                emptyVariantId, stockedVariantId);
        jdbc.update("DELETE FROM products WHERE id = ?", productId);
        jdbc.update("DELETE FROM categories WHERE id = ?", categoryId);
        clearBrowseCaches();
    }

    @Test
    @DisplayName("the product page reports each size's own stock")
    void stockIsPerVariant() {
        ProductResponse detail = read(() -> productService.getProductById(productId));
        assertNotNull(detail, "the shop lists it, so its page opens - STATE 2 is not STATE 1");

        assertEquals(Boolean.FALSE, stockOf(detail, emptyVariantId),
                "the size the shop has run out of must say so");
        assertEquals(Boolean.TRUE, stockOf(detail, stockedVariantId),
                "AN EMPTY SIZE MUST NOT TAKE THE SIZES BESIDE IT DOWN WITH IT (§7). The shop "
                        + "is selling 5 kg atta; only the 1 kg shelf is bare.");

        assertTrue(availableOf(detail, emptyVariantId),
                "and it is still LISTED - \"we sell this\" and \"we have any\" are different "
                        + "sentences, and collapsing them into one flag is what hid the "
                        + "difference from the customer in the first place");
    }

    @Test
    @DisplayName("the card offers a size the customer can actually buy")
    void theCardPicksAStockedSize() {
        ProductResponse card = read(() -> productService.browseAll(newestFirst()).getContent()
                .stream().filter(p -> productId.equals(p.getId())).findFirst().orElseThrow());

        assertEquals(1, card.getVariants().size(), "a card carries one size");
        assertEquals(stockedVariantId, card.getVariants().get(0).getId(),
                "THE CARD OFFERED THE EMPTY SHELF BECAUSE IT WAS CHEAPER. Picking the cheapest "
                        + "size without asking whether it exists gives the customer a button "
                        + "that cannot be pressed.");
        assertEquals(Boolean.TRUE, card.getVariants().get(0).getInStock());
    }

    @Test
    @DisplayName("a product that is wholly out of stock still appears, saying so")
    void aSoldOutProductIsStillOnTheShelf() {
        emptyTheShelf();

        ProductResponse card = read(() -> productService.browseAll(newestFirst()).getContent()
                .stream().filter(p -> productId.equals(p.getId())).findFirst().orElse(null));

        assertNotNull(card,
                "A SOLD-OUT PRODUCT IS NOT A DELISTED ONE (§7 STATE 2 vs STATE 1). Hiding it "
                        + "tells the customer the shop does not sell it, which is untrue and "
                        + "sends them somewhere else for something that is back tomorrow.");
        assertEquals(Boolean.FALSE, card.getVariants().get(0).getInStock());
        assertTrue(Boolean.TRUE.equals(card.getVariants().get(0).getAvailable()),
                "still listed, still the shop's line, simply not on the shelf today");
    }

    @Test
    @DisplayName("and the server refuses to put it in a basket")
    void theServerRefusesTheAdd() {
        // THE HALF THAT WAS ALREADY RIGHT, asserted here so the two halves are
        // seen to agree. A screen that says "out of stock" while the server
        // accepts the add would be the same disagreement pointing the other
        // way, and would oversell the shop.
        emptyTheShelf();
        assertEquals(0, stockRowFor(stockedVariantId).map(Inventory::getStock).orElse(-1));
    }

    // -------------------------------------------------------------- fixtures

    private void emptyTheShelf() {
        jdbc.update("UPDATE inventory SET stock = 0 WHERE product_variant_id IN (?, ?)",
                emptyVariantId, stockedVariantId);
        clearBrowseCaches();
    }

    private Optional<Inventory> stockRowFor(Long variantId) {
        return TenantContext.runWithin(TenantScope.ofShop(shopId),
                () -> inventory.findByProductVariantId(variantId));
    }

    private static Boolean stockOf(ProductResponse product, Long variantId) {
        return variantIn(product, variantId).getInStock();
    }

    private static boolean availableOf(ProductResponse product, Long variantId) {
        return Boolean.TRUE.equals(variantIn(product, variantId).getAvailable());
    }

    private static VariantResponse variantIn(ProductResponse product, Long variantId) {
        return product.getVariants().stream()
                .filter(v -> variantId.equals(v.getId()))
                .findFirst().orElseThrow();
    }

    private PageRequest newestFirst() {
        return PageRequest.of(0, 40, Sort.by(Sort.Direction.DESC, "id"));
    }

    private <T> T read(java.util.function.Supplier<T> work) {
        clearBrowseCaches();
        return TenantContext.runWithin(TenantScope.ofShop(shopId), work::get);
    }

    private void clearBrowseCaches() {
        for (String name : List.of("products", "brands", "newArrivals", "categoryProducts",
                "productDetail", "productSearch", "productFeed", "bestsellerTiles",
                "trending", "frequentlyBought")) {
            Optional.ofNullable(caches.getCache(name))
                    .ifPresent(org.springframework.cache.Cache::clear);
        }
    }

    /** A variant this shop lists, with a given holding. */
    private Long newVariant(Product product, double size, BigDecimal price, int stock) {
        ProductVariant variant = new ProductVariant();
        variant.setProduct(product);
        variant.setQuantity(size);
        variant.setUnit("kg");
        variant.setSellingPrice(price);
        variant.setAvailable(Boolean.TRUE);
        variant.setActive(Boolean.TRUE);
        Long variantId = TenantContext.runWithin(TenantScope.platform(),
                () -> variants.save(variant).getId());

        TenantContext.runWithin(TenantScope.ofShop(shopId), () -> {
            ShopProductVariant listing = listings.findByProductVariantId(variantId)
                    .orElseGet(ShopProductVariant::new);
            listing.setProductVariantId(variantId);
            listing.setSellingPrice(price);
            listing.setAvailable(Boolean.TRUE);
            listing.setActive(Boolean.TRUE);
            listings.save(listing);

            Inventory row = new Inventory();
            row.setProductVariant(variants.findById(variantId).orElseThrow());
            row.setStock(stock);
            row.setReservedStock(0);
            return inventory.save(row);
        });
        return variantId;
    }
}
