package com.gpstore.platform;

import com.gpstore.entity.Role;
import com.gpstore.security.AuthenticatedUser;
import com.gpstore.security.RolePermissions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * The app can tell which shop a basket line and an order came from.
 *
 * WHY THIS IS A BACKEND TEST FOR A FRONTEND PROBLEM. The Flutter apps have to
 * group a basket by shop and label an order with its seller. Neither was
 * possible: CartItemResponse carried no shop and OrderResponse carried no shop
 * either, so a client wanting to group had only two options - fetch something
 * else and guess, or invent a grouping the server never decided. Both are the
 * thing this whole transformation exists to prevent, and both would have put
 * marketplace logic in Dart.
 *
 * So the fields were added to the contract rather than reconstructed in the
 * app, and they are pinned here, where they can actually be executed. The
 * Dart side of Slice 12 cannot be run in this container at all - see the
 * slice report - which makes it more important, not less, that the shape it
 * consumes is proved.
 *
 * ADDITIVE, AND THAT IS ASSERTED. Every key an existing client reads is still
 * there; the single-shop app that ignores both new fields behaves exactly as
 * it did (§96, §12).
 */
@SpringBootTest(properties = {
        "outbox.initial-delay-ms=3600000",
        "outbox.drain-interval-ms=3600000",
        "payment.expiry-initial-delay-ms=3600000",
        "idempotency.cleanup-initial-delay-ms=3600000",
        "otp.cleanup-initial-delay-ms=3600000",
        "delivery.late-flag-initial-delay-ms=3600000"
})
@AutoConfigureMockMvc
@DisplayName("A basket line and an order both say which shop they are from")
class AppContractsCarryTheShopTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ShopRepository shops;
    @Autowired private PlatformProperties platform;

    private final String tag = "ctr" + System.nanoTime();

    private long shopOne;
    private Long customerId;
    private Long variantId;
    private long orderId;

    @BeforeEach
    void aCustomerWithABasketAndAnOrder() {
        shopOne = shops.findByCode(platform.getFirstShopCode()).orElseThrow().getId();

        jdbc.update("""
                INSERT INTO customers (full_name, email, mobile_number, password, role, active)
                VALUES (?, ?, ?, 'not-a-real-hash', 'CUSTOMER', true)
                """, "Contract fixture " + tag, tag + "@example.test",
                "9" + (100000000 + (int) (Math.random() * 899999999)));
        customerId = jdbc.queryForObject(
                "SELECT id FROM customers WHERE email = ?", Long.class, tag + "@example.test");

        variantId = jdbc.queryForObject(
                "SELECT id FROM product_variants ORDER BY id LIMIT 1", Long.class);

        jdbc.update("INSERT INTO carts (customer_id) VALUES (?)", customerId);
        Long cartId = jdbc.queryForObject(
                "SELECT id FROM carts WHERE customer_id = ?", Long.class, customerId);
        jdbc.update("""
                INSERT INTO cart_items (cart_id, product_variant_id, quantity, price, total_price,
                                        shop_id)
                VALUES (?, ?, 1, 50.00, 50.00, ?)
                """, cartId, variantId, shopOne);

        jdbc.update("""
                INSERT INTO orders (customer_id, order_number, total_amount, order_status,
                                    payment_status, order_date, shop_id)
                VALUES (?, ?, 250.00, 'DELIVERED', 'SUCCESS', now(), ?)
                """, customerId, "CTR-" + tag, shopOne);
        orderId = jdbc.queryForObject(
                "SELECT id FROM orders WHERE order_number = ?", Long.class, "CTR-" + tag);
    }

    @AfterEach
    void tidyUp() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
        jdbc.update("DELETE FROM orders WHERE id = ?", orderId);
        jdbc.update("DELETE FROM cart_items WHERE cart_id IN "
                + "(SELECT id FROM carts WHERE customer_id = ?)", customerId);
        jdbc.update("DELETE FROM carts WHERE customer_id = ?", customerId);
        jdbc.update("DELETE FROM customers WHERE id = ?", customerId);
    }

    @Test
    @DisplayName("every basket line names its shop, so the app can group without guessing")
    void aBasketLineCarriesItsShop() throws Exception {
        String body = mockMvc.perform(get("/api/carts/mine")
                        .with(authentication(as(Role.CUSTOMER))))
                .andReturn().getResponse().getContentAsString();

        assertTrue(body.contains("\"shopId\":" + shopOne),
                "a basket line must say which shelf it came off. Without it a client wanting to "
                        + "group a multi-shop basket has to invent the grouping, which is exactly "
                        + "the marketplace logic that must not live in Dart: " + body);
    }

    @Test
    @DisplayName("the basket names the shops it spans, so a group can be labelled")
    void theBasketNamesItsShops() throws Exception {
        String body = mockMvc.perform(get("/api/carts/mine")
                        .with(authentication(as(Role.CUSTOMER))))
                .andReturn().getResponse().getContentAsString();

        assertTrue(body.contains("\"shops\":["),
                "the basket must list the shops it spans: " + body);
        assertTrue(body.contains("\"shopId\":" + shopOne),
                "the entry must name the shop by id even when there is one: " + body);

        // THE NAME IS FETCHED ONLY UNDER A MARKETPLACE, deliberately. Under
        // one shop the customer knows whose shop they are in, nothing displays
        // a name, and looking one up would be a query per cart read on the
        // most-called authenticated endpoint - which CheckoutPerformanceTest
        // refuses. MarketplaceCartLabelsTest covers the multi-shop half, where
        // the name is the whole point.
        assertTrue(body.contains("\"shopName\":null") || body.contains("\"shopName\":\""),
                "the field must exist either way so a client can read it without "
                        + "branching on deployment mode: " + body);
    }

    @Test
    @DisplayName("an order says who the customer bought from")
    void anOrderCarriesItsShop() throws Exception {
        String body = mockMvc.perform(get("/api/orders/my-orders")
                        .with(authentication(as(Role.CUSTOMER))))
                .andReturn().getResponse().getContentAsString();

        assertTrue(body.contains("\"shopId\":" + shopOne),
                "an order must name its shop - a split checkout is several orders on one day and "
                        + "they are indistinguishable without it: " + body);
        assertTrue(body.contains("\"shopName\":"),
                "the seller's name belongs beside the id: " + body);
    }

    @Test
    @DisplayName("nothing an existing client reads was removed")
    void theChangeIsAdditive() throws Exception {
        String cart = mockMvc.perform(get("/api/carts/mine")
                        .with(authentication(as(Role.CUSTOMER))))
                .andReturn().getResponse().getContentAsString();

        // The keys the shipped Flutter CartItemModel deserialises. §12: a
        // released app on a customer's phone keeps working, and a contract
        // test is the only thing that can promise that from here.
        for (String key : List.of("cartItemId", "productId", "variantId", "quantity",
                "price", "totalPrice", "available", "totalAmount", "totalItems")) {
            assertTrue(cart.contains("\"" + key + "\""),
                    "the app on a customer's phone reads " + key + " and it is gone: " + cart);
        }

        String orders = mockMvc.perform(get("/api/orders/my-orders")
                        .with(authentication(as(Role.CUSTOMER))))
                .andReturn().getResponse().getContentAsString();
        for (String key : List.of("orderId", "orderNumber", "totalAmount", "orderStatus",
                "orderDate")) {
            assertTrue(orders.contains("\"" + key + "\""),
                    "the app reads " + key + " on an order and it is gone: " + orders);
        }
    }

    private UsernamePasswordAuthenticationToken as(Role role) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        for (String authority : RolePermissions.authorityNames(role)) {
            authorities.add(new SimpleGrantedAuthority(authority));
        }
        return new UsernamePasswordAuthenticationToken(
                new AuthenticatedUser(customerId, tag + "@example.test", role.name()),
                null, authorities);
    }
}
