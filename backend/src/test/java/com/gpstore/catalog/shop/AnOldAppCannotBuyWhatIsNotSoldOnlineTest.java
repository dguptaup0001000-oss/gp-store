package com.gpstore.catalog.shop;

import com.gpstore.entity.Address;
import com.gpstore.entity.Category;
import com.gpstore.entity.Customer;
import com.gpstore.entity.Inventory;
import com.gpstore.entity.Product;
import com.gpstore.entity.ProductVariant;
import com.gpstore.entity.Role;
import com.gpstore.repository.AddressRepository;
import com.gpstore.repository.CategoryRepository;
import com.gpstore.repository.CustomerRepository;
import com.gpstore.repository.InventoryRepository;
import com.gpstore.repository.ProductRepository;
import com.gpstore.repository.ProductVariantRepository;
import com.gpstore.security.AuthenticatedUser;
import com.gpstore.security.RolePermissions;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * A custom HTTP client, holding a Visit-to-Buy variant id, cannot buy it.
 *
 * <h2>Why this exists beside the service-level test</h2>
 *
 * <p>{@link WhatIsNotSoldOnlineCannotBeBoughtOnlineTest} proves the same rule
 * through {@code CartService}. That is the right place to prove the rule, and
 * it is not the right place to prove there is no way around it: a reader who
 * has not read the whole call graph cannot tell from a service test whether
 * some controller reaches the database by another road.
 *
 * <p>So this one sends REAL HTTP REQUESTS, with a real customer's
 * authentication, at every published endpoint that could put an item into an
 * order - the exact shape of request somebody would build from a network tab
 * or an old APK. The assertion is the response code.
 *
 * <h2>The case that is not about forgery at all</h2>
 *
 * <p>The interesting failure needs no attacker. A customer adds a ring to
 * their basket on Monday while the shop still sells it online; on Tuesday the
 * shopkeeper decides rings are viewing-only and switches the listing to Visit
 * to Buy; on Wednesday the customer opens the app they left open and taps
 * Place Order. If the mode were only checked when the item entered the
 * basket, that order would go through: real money taken for something the
 * shop never agreed to ship, and a refund somebody has to unwind by hand.
 *
 * <p>{@code CheckoutIsAlsoChecked} is that story, told twice - once at the
 * preview a customer sees and once at the order itself.
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
@AutoConfigureMockMvc
@DisplayName("An old app cannot buy what the shop does not sell online")
class AnOldAppCannotBuyWhatIsNotSoldOnlineTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private CustomerRepository customers;
    @Autowired private AddressRepository addresses;
    @Autowired private CategoryRepository categories;
    @Autowired private ProductRepository products;
    @Autowired private ProductVariantRepository variants;
    @Autowired private InventoryRepository inventories;

    @Value("${store.latitude}") private double storeLatitude;
    @Value("${store.longitude}") private double storeLongitude;

    private final String tag = "oldapp" + System.nanoTime();

    private Long customerId;
    private Long addressId;
    private Long categoryId;
    private Long productId;
    private Long variantId;
    private Long shopId;

    @BeforeEach
    void aShopWithOneRingOnTheShelf() {
        Customer customer = new Customer();
        customer.setFullName("Old App " + tag);
        customer.setEmail(tag + "@example.test");
        customer.setMobileNumber("9" + String.valueOf(System.nanoTime()).substring(0, 9));
        customer.setPassword("not-a-real-hash");
        customer.setEnabled(true);
        customer.setActive(true);
        customer.setRole(Role.CUSTOMER);
        customerId = customers.save(customer).getId();

        Address address = new Address();
        address.setCustomer(customer);
        address.setFullName(customer.getFullName());
        address.setMobileNumber(customer.getMobileNumber());
        address.setHouseNo("1");
        address.setArea("Test Area");
        address.setCity("Test City");
        address.setState("Test State");
        address.setPincode("110001");
        address.setCountry("India");
        // The shop's own coordinates, so a checkout can never fail for a
        // delivery-radius reason that has nothing to do with the subject.
        address.setLatitude(storeLatitude);
        address.setLongitude(storeLongitude);
        address.setDefaultAddress(true);
        addressId = addresses.save(address).getId();

        Category category = new Category();
        category.setName("Old app category " + tag);
        category.setActive(true);
        category.setGstRate(new BigDecimal("5"));
        categoryId = categories.save(category).getId();

        Product product = new Product();
        product.setName("Gold ring " + tag);
        product.setCategory(category);
        product.setActive(true);
        productId = products.save(product).getId();

        ProductVariant variant = new ProductVariant();
        variant.setProduct(product);
        variant.setQuantity(1.0);
        variant.setUnit("pc");
        variant.setMrp(new BigDecimal("100.00"));
        variant.setSellingPrice(new BigDecimal("90.00"));
        variant.setAvailable(Boolean.TRUE);
        variant.setActive(Boolean.TRUE);
        variantId = variants.save(variant).getId();

        Inventory stock = new Inventory();
        stock.setProductVariant(variants.findById(variantId).orElseThrow());
        stock.setStock(50);
        inventories.save(stock);

        shopId = jdbc.queryForObject("SELECT id FROM shops ORDER BY id LIMIT 1", Long.class);
        assertNotNull(shopId, "the deployment's first shop must exist");
        jdbc.update("INSERT INTO shop_product_variants "
                + "(shop_id, product_variant_id, selling_price, mrp, available, active, "
                + " commerce_mode, price_mode, created_at, updated_at) "
                + "VALUES (?, ?, 90, 100, true, true, 'ONLINE_PURCHASE', 'EXACT_PRICE', now(), now())",
                shopId, variantId);
    }

    @AfterEach
    void tidyUp() {
        SecurityContextHolder.clearContext();
        jdbc.update("DELETE FROM idempotency_records WHERE customer_id = ?", customerId);
        for (String child : new String[] {
                "order_scan_events", "order_returns", "deliveries",
                "invoices", "notifications", "payments", "order_items"}) {
            jdbc.update("DELETE FROM " + child + " WHERE order_id IN "
                    + "(SELECT id FROM orders WHERE customer_id = ?)", customerId);
        }
        jdbc.update("DELETE FROM orders WHERE customer_id = ?", customerId);
        jdbc.update("DELETE FROM cart_items WHERE cart_id IN "
                + "(SELECT id FROM carts WHERE customer_id = ?)", customerId);
        jdbc.update("DELETE FROM carts WHERE customer_id = ?", customerId);
        jdbc.update("DELETE FROM addresses WHERE id = ?", addressId);
        jdbc.update("DELETE FROM inventory WHERE product_variant_id = ?", variantId);
        jdbc.update("DELETE FROM shop_product_variants WHERE product_variant_id = ?", variantId);
        jdbc.update("DELETE FROM product_variants WHERE id = ?", variantId);
        jdbc.update("DELETE FROM products WHERE id = ?", productId);
        jdbc.update("DELETE FROM categories WHERE id = ?", categoryId);
        jdbc.update("DELETE FROM customers WHERE id = ?", customerId);
    }

    // ------------------------------------------------------------------

    @Nested
    @DisplayName("The basket door")
    class TheBasketDoor {

        @Test
        @DisplayName("a Visit-to-Buy variant id is refused at add-to-cart")
        void visitToBuyRefusedAtAdd() throws Exception {
            listAs("VISIT_TO_BUY", "EXACT_PRICE");

            MvcResult result = addToCart();

            assertEquals(409, result.getResponse().getStatus(),
                    "A CUSTOM HTTP CLIENT PUT A VISIT-TO-BUY ITEM IN A BASKET. "
                            + "The app's missing ADD button is a courtesy, not a control: "
                            + result.getResponse().getContentAsString());
            assertTrue(result.getResponse().getContentAsString().contains("Visit the shop"),
                    "the refusal must say which of the two things is true - 'unavailable' "
                            + "sends a customer who could have walked in to a competitor: "
                            + result.getResponse().getContentAsString());
            assertEquals(0, cartLines(), "nothing may be left in the basket");
        }

        @Test
        @DisplayName("a service is refused too, in its own words")
        void serviceRefusedAtAdd() throws Exception {
            listAs("SERVICE_AT_SHOP", "EXACT_PRICE");

            MvcResult result = addToCart();

            assertEquals(409, result.getResponse().getStatus(),
                    result.getResponse().getContentAsString());
            assertTrue(result.getResponse().getContentAsString().contains("service"),
                    "a haircut is not an out-of-stock packet of atta: "
                            + result.getResponse().getContentAsString());
            assertEquals(0, cartLines());
        }

        @Test
        @DisplayName("an ordinary online listing still goes in")
        void onlineStillWorks() throws Exception {
            MvcResult result = addToCart();

            assertEquals(200, result.getResponse().getStatus(),
                    "THE FIXTURE IS BROKEN, not the rule - if this fails the refusals "
                            + "above prove nothing: " + result.getResponse().getContentAsString());
            assertEquals(1, cartLines());
        }
    }

    @Nested
    @DisplayName("The checkout door, for a basket filled before the shop changed its mind")
    class CheckoutIsAlsoChecked {

        /**
         * Monday: the ring is sold online and goes into the basket.
         * Tuesday: the shopkeeper switches it to Visit to Buy.
         * Wednesday: the customer taps Place Order on the app they left open.
         */
        @BeforeEach
        void aBasketThatOutlivedTheListing() throws Exception {
            assertEquals(200, addToCart().getResponse().getStatus(),
                    "the fixture basket was not built");
            listAs("VISIT_TO_BUY", "EXACT_PRICE");
        }

        @Test
        @DisplayName("the checkout preview refuses, so the customer is told before paying")
        void previewRefuses() throws Exception {
            MvcResult result = mockMvc.perform(get("/api/orders/checkout-preview")
                            .param("addressId", String.valueOf(addressId))
                            .with(authentication(asCustomer())))
                    .andReturn();

            assertEquals(409, result.getResponse().getStatus(),
                    "the preview priced a basket it cannot sell: "
                            + result.getResponse().getContentAsString());
        }

        @Test
        @DisplayName("placing the order refuses, and no order is written")
        void placingRefuses() throws Exception {
            MvcResult result = mockMvc.perform(post("/api/orders/place")
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"addressId\": %d, \"paymentMethod\": \"COD\"}"
                                    .formatted(addressId))
                            .with(authentication(asCustomer())))
                    .andReturn();

            assertEquals(409, result.getResponse().getStatus(),
                    "AN ORDER WAS TAKEN FOR AN ITEM THE SHOP NO LONGER SELLS ONLINE. "
                            + "Nobody forged anything - the basket simply outlived the "
                            + "listing: " + result.getResponse().getContentAsString());

            Integer placed = jdbc.queryForObject(
                    "SELECT count(*) FROM orders WHERE customer_id = ?", Integer.class, customerId);
            assertEquals(0, placed,
                    "a refused checkout must leave no order behind");
        }

        @Test
        @DisplayName("a service left in a basket is refused at checkout too")
        void serviceInAStaleBasketRefuses() throws Exception {
            listAs("SERVICE_AT_SHOP", "EXACT_PRICE");

            MvcResult result = mockMvc.perform(post("/api/orders/place")
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"addressId\": %d, \"paymentMethod\": \"COD\"}"
                                    .formatted(addressId))
                            .with(authentication(asCustomer())))
                    .andReturn();

            assertEquals(409, result.getResponse().getStatus(),
                    result.getResponse().getContentAsString());
            assertEquals(0, (int) jdbc.queryForObject(
                    "SELECT count(*) FROM orders WHERE customer_id = ?", Integer.class, customerId));
        }

        @Test
        @DisplayName("and the same basket checks out once the shop sells it online again")
        void restoringTheModeLetsItThrough() throws Exception {
            listAs("ONLINE_PURCHASE", "EXACT_PRICE");

            MvcResult result = mockMvc.perform(post("/api/orders/place")
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"addressId\": %d, \"paymentMethod\": \"COD\"}"
                                    .formatted(addressId))
                            .with(authentication(asCustomer())))
                    .andReturn();

            assertEquals(200, result.getResponse().getStatus(),
                    "THE REFUSALS ABOVE PROVE NOTHING IF CHECKOUT IS SIMPLY BROKEN. "
                            + "This is the same basket, the same customer and the same "
                            + "request, differing only in the commerce mode: "
                            + result.getResponse().getContentAsString());
        }
    }

    // ------------------------------------------------------------- helpers

    private MvcResult addToCart() throws Exception {
        return mockMvc.perform(post("/api/carts/add")
                        .param("variantId", String.valueOf(variantId))
                        .param("quantity", "1")
                        .with(authentication(asCustomer())))
                .andReturn();
    }

    /** What the shopkeeper's own screen writes when they change how they sell. */
    private void listAs(String commerceMode, String priceMode) {
        jdbc.update("UPDATE shop_product_variants SET commerce_mode = ?, price_mode = ? "
                        + "WHERE shop_id = ? AND product_variant_id = ?",
                commerceMode, priceMode, shopId, variantId);
    }

    private int cartLines() {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM cart_items WHERE cart_id IN "
                        + "(SELECT id FROM carts WHERE customer_id = ?)",
                Integer.class, customerId);
        return count == null ? 0 : count;
    }

    private UsernamePasswordAuthenticationToken asCustomer() {
        List<GrantedAuthority> authorities = new ArrayList<>();
        for (String authority : RolePermissions.authorityNames(Role.CUSTOMER)) {
            authorities.add(new SimpleGrantedAuthority(authority));
        }
        return new UsernamePasswordAuthenticationToken(
                new AuthenticatedUser(customerId, tag + "@example.test", Role.CUSTOMER.name()),
                null, authorities);
    }
}
