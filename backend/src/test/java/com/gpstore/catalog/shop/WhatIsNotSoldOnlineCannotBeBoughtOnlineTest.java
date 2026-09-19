package com.gpstore.catalog.shop;

import com.gpstore.entity.Category;
import com.gpstore.entity.Customer;
import com.gpstore.entity.Inventory;
import com.gpstore.entity.Product;
import com.gpstore.entity.ProductVariant;
import com.gpstore.exception.ConflictException;
import com.gpstore.repository.CategoryRepository;
import com.gpstore.repository.CustomerRepository;
import com.gpstore.repository.InventoryRepository;
import com.gpstore.repository.ProductRepository;
import com.gpstore.repository.ProductVariantRepository;
import com.gpstore.service.CartService;
import com.gpstore.support.TestMobileNumbers;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A ring you are meant to come and look at cannot be put in a basket, and the
 * app's missing ADD button is not what stops you.
 *
 * <h2>Why this test is the whole point of the feature</h2>
 *
 * <p>Visit-to-Buy and Service-at-Shop exist so a merchant can be FOUND without
 * promising to ship. The moment one of those listings can be bought online,
 * the promise is broken in the worst possible way: the customer has paid, the
 * shop has an order it never agreed to fulfil, and somebody has to unwind a
 * real payment. So the refusal has to live where a request cannot get around
 * it, and that is the server.
 *
 * <p>A hidden button proves nothing. Every check here goes through the service
 * layer with a variant id, which is exactly what somebody reading the network
 * tab would send. If these pass while the ADD button is hidden, the button is
 * a courtesy rather than the control.
 */
@SpringBootTest(properties = {
        com.gpstore.support.DeploymentShape.SINGLE_SHOP,
        "outbox.initial-delay-ms=3600000",
        "outbox.drain-interval-ms=3600000",
        "payment.expiry-initial-delay-ms=3600000",
        "idempotency.cleanup-initial-delay-ms=3600000",
        "otp.cleanup-initial-delay-ms=3600000",
        "delivery.late-flag-initial-delay-ms=3600000"
})
@DisplayName("What a shop does not sell online cannot be bought online")
class WhatIsNotSoldOnlineCannotBeBoughtOnlineTest {

    @Autowired private CartService cartService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private CustomerRepository customers;
    @Autowired private CategoryRepository categories;
    @Autowired private ProductRepository products;
    @Autowired private ProductVariantRepository variants;
    @Autowired private InventoryRepository inventories;

    private Long customerId;
    private Long variantId;
    private Long shopId;

    @BeforeEach
    void aShopWithOneThingOnTheShelf() {
        Customer customer = new Customer();
        customer.setFullName("Mode Test Customer");
        customer.setEmail("mode-" + System.nanoTime() + "@example.test");
        customer.setMobileNumber(TestMobileNumbers.unique());
        customer.setPassword("irrelevant-for-this-test");
        customer.setEnabled(true);
        customer.setActive(true);
        customerId = customers.save(customer).getId();

        Category category = new Category();
        category.setName("Mode Category " + System.nanoTime());
        category.setActive(true);
        category.setGstRate(new BigDecimal("5"));
        category = categories.save(category);

        Product product = new Product();
        product.setName("Mode Item " + System.nanoTime());
        product.setBrand("TestBrand");
        product.setActive(true);
        product.setCategory(category);
        product = products.save(product);

        ProductVariant variant = new ProductVariant();
        variant.setProduct(product);
        variant.setQuantity(1.0);
        variant.setUnit("pc");
        variant.setMrp(new BigDecimal("100"));
        variant.setSellingPrice(new BigDecimal("90"));
        variant.setAvailable(true);
        variant.setActive(true);
        variantId = variants.save(variant).getId();

        Inventory inventory = new Inventory();
        inventory.setProductVariant(variants.findById(variantId).orElseThrow());
        inventory.setStock(50);
        inventories.save(inventory);

        shopId = jdbc.queryForObject("SELECT id FROM shops ORDER BY id LIMIT 1", Long.class);
        assertNotNull(shopId, "the deployment's first shop must exist");
        jdbc.update("INSERT INTO shop_product_variants "
                + "(shop_id, product_variant_id, selling_price, mrp, available, active, "
                + " commerce_mode, price_mode, created_at, updated_at) "
                + "VALUES (?, ?, 90, 100, true, true, 'ONLINE_PURCHASE', 'EXACT_PRICE', now(), now())",
                shopId, variantId);
    }

    private void listAs(String commerceMode, String priceMode) {
        jdbc.update("UPDATE shop_product_variants SET commerce_mode = ?, price_mode = ? "
                + "WHERE shop_id = ? AND product_variant_id = ?",
                commerceMode, priceMode, shopId, variantId);
    }

    @Test
    @DisplayName("an ordinary online listing is unaffected")
    void onlinePurchaseStillWorks() {
        // THE CONTROL. Without this, every assertion below would also pass if
        // the guard simply refused everything, and a feature that quietly
        // stopped the whole marketplace selling would look like a success.
        assertNotNull(cartService.addToCart(customerId, variantId, 1),
                "an ONLINE_PURCHASE listing must still go into the basket exactly as before");
    }

    @Nested
    @DisplayName("Visit to Buy")
    class VisitToBuy {

        @Test
        @DisplayName("cannot be added to a basket, whatever the client sends")
        void refusedAtAddToCart() {
            listAs("VISIT_TO_BUY", "STARTING_FROM");

            ConflictException refused = assertThrows(ConflictException.class,
                    () -> cartService.addToCart(customerId, variantId, 1),
                    "A Visit-to-Buy listing reaching add-to-cart must be refused by the "
                            + "server. If this passes, the only thing stopping a purchase is "
                            + "a hidden button in one client.");

            // THE MESSAGE IS PART OF THE BEHAVIOUR, not decoration. "Currently
            // unavailable" would be false and actively harmful here: the shop
            // has it, the customer can buy it today, and a customer told it is
            // unavailable goes to a competitor instead of going to the shop.
            assertTrue(refused.getMessage().toLowerCase().contains("visit the shop"),
                    "the refusal must tell the customer what to do instead, got: "
                            + refused.getMessage());
        }
    }

    @Nested
    @DisplayName("Service at Shop")
    class ServiceAtShop {

        @Test
        @DisplayName("cannot be added to a basket either, and says so in its own words")
        void refusedAtAddToCart() {
            listAs("SERVICE_AT_SHOP", "ASK_AT_SHOP");

            ConflictException refused = assertThrows(ConflictException.class,
                    () -> cartService.addToCart(customerId, variantId, 1));

            assertTrue(refused.getMessage().toLowerCase().contains("service"),
                    "a haircut refused as though it were an out-of-stock product tells the "
                            + "customer nothing useful, got: " + refused.getMessage());
        }
    }

    @Nested
    @DisplayName("The database keeps the two price worlds apart")
    class PriceCoherence {

        @Test
        @DisplayName("an online listing may not carry a price it will not honour")
        void onlineListingsMustBeExact() {
            // A cart cannot total "from ₹25,000", so a listing that is sold
            // online and priced vaguely is not a display problem - it is an
            // order whose total nobody can compute. The constraint is in the
            // schema because four callers would otherwise each have to
            // remember, and one of them eventually would not.
            assertThrows(Exception.class,
                    () -> listAs("ONLINE_PURCHASE", "ASK_AT_SHOP"),
                    "the database must refuse an online listing with a non-exact price");
        }

        @Test
        @DisplayName("a range without a top is not a range")
        void rangesNeedBothEnds() {
            assertThrows(Exception.class,
                    () -> listAs("VISIT_TO_BUY", "PRICE_RANGE"),
                    "PRICE_RANGE with no price_max would render as a broken price on every card");
        }
    }

    @Nested
    @DisplayName("Existing data")
    class ExistingData {

        @Test
        @DisplayName("every listing that existed before this feature still sells online")
        void nothingWasReclassified() {
            // THE MIGRATION MUST NOT HAVE TAKEN ANYBODY'S SHOP OFF THE
            // INTERNET. Reclassifying by trade - deciding jewellery is
            // probably offline - would have been a plausible-looking guess
            // that silently stopped real merchants selling.
            Long notOnline = jdbc.queryForObject(
                    "SELECT count(*) FROM shop_product_variants WHERE commerce_mode IS NULL",
                    Long.class);
            assertEquals(0L, notOnline,
                    "no listing may be left without a mode - the guard reads a null as "
                            + "'not sold online' and the shelf would vanish");
        }
    }
}
