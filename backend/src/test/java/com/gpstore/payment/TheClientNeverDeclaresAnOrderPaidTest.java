package com.gpstore.payment;

import com.gpstore.entity.Address;
import com.gpstore.entity.Category;
import com.gpstore.entity.Customer;
import com.gpstore.entity.Inventory;
import com.gpstore.entity.Order;
import com.gpstore.entity.Product;
import com.gpstore.entity.ProductVariant;
import com.gpstore.entity.Role;
import com.gpstore.enums.PaymentStatus;
import com.gpstore.repository.AddressRepository;
import com.gpstore.repository.CategoryRepository;
import com.gpstore.repository.CustomerRepository;
import com.gpstore.repository.InventoryRepository;
import com.gpstore.repository.OrderRepository;
import com.gpstore.repository.ProductRepository;
import com.gpstore.repository.ProductVariantRepository;
import com.gpstore.security.AuthenticatedUser;
import com.gpstore.security.RolePermissions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * THE CLIENT NEVER DECLARES AN ORDER PAID. (§17 TEST 15.)
 *
 * <p>The most valuable thing an attacker can put in a checkout body is
 * {@code "paymentStatus": "SUCCESS"}. It costs nothing to try, it needs no stolen
 * credential - their own account is enough - and if it works the shop packs
 * and delivers groceries that were never paid for. There is no alert for it,
 * either: the order looks exactly like a successful prepaid order, because as
 * far as every screen is concerned it is one.
 *
 * <p>THE CODE IS ALREADY RIGHT, AND THAT IS PRECISELY WHY THIS TEST EXISTS.
 * {@code PlaceOrderRequest} carries three fields - address, payment method,
 * coupon - and is annotated {@code @JsonIgnoreProperties(ignoreUnknown =
 * true)}, so an unexpected field is dropped in silence. Silence is the
 * problem: nothing anywhere fails if somebody adds a {@code paymentStatus}
 * setter to that DTO "so the app can pass it through", or swaps the
 * annotation, or replaces the DTO with a Map, or binds the entity directly.
 * Each of those is a one-line change that looks harmless in review and
 * hands away free groceries.
 *
 * <p>So this asserts the OUTCOME rather than the annotation: send the hostile
 * body over real HTTP, then read the row back out of the database and check
 * the server's own numbers are what got written. Any of the changes above
 * fails here by name.
 *
 * <p>EACH CHECKOUT CARRIES A REAL Idempotency-Key, because the endpoint
 * requires one - see IdempotencyHeaderContractTest. A fresh UUID per call, so
 * nothing here is answered out of the idempotency cache instead of being
 * processed: a replayed response would return the FIRST attempt's order and
 * these assertions would then be reading a row the hostile body never
 * touched.
 */
@SpringBootTest(properties = {
        // The deployment this test describes - see DeploymentShape.
        com.gpstore.support.DeploymentShape.SINGLE_SHOP,
        "outbox.initial-delay-ms=3600000",
        "outbox.drain-interval-ms=3600000",
        "payment.expiry-initial-delay-ms=3600000",
        "idempotency.cleanup-initial-delay-ms=3600000",
        "otp.cleanup-initial-delay-ms=3600000",
        "delivery.late-flag-initial-delay-ms=3600000"
})
@AutoConfigureMockMvc
@DisplayName("The client never declares an order paid")
class TheClientNeverDeclaresAnOrderPaidTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private CustomerRepository customers;
    @Autowired private AddressRepository addresses;
    @Autowired private CategoryRepository categories;
    @Autowired private ProductRepository products;
    @Autowired private ProductVariantRepository variants;
    @Autowired private InventoryRepository inventories;
    @Autowired private OrderRepository orders;

    @Value("${store.latitude}") private double storeLatitude;
    @Value("${store.longitude}") private double storeLongitude;

    private static final BigDecimal SELLING_PRICE = new BigDecimal("90.00");

    private final String tag = "paidclaim" + System.nanoTime();

    private Long customerId;
    private Long addressId;
    private Long categoryId;
    private Long productId;
    private Long variantId;

    @BeforeEach
    void aCustomerWithOneThingInTheirBasket() throws Exception {
        Customer customer = new Customer();
        customer.setFullName("Claimant " + tag);
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
        // The shop's own coordinates, so delivery is always in range and the
        // checkout cannot fail for a reason unrelated to what is under test.
        address.setLatitude(storeLatitude);
        address.setLongitude(storeLongitude);
        address.setDefaultAddress(true);
        addressId = addresses.save(address).getId();

        Category category = new Category();
        category.setName("Claim category " + tag);
        category.setActive(true);
        category.setGstRate(new BigDecimal("5"));
        categoryId = categories.save(category).getId();

        Product product = new Product();
        product.setName("Claim item " + tag);
        product.setCategory(category);
        product.setActive(true);
        productId = products.save(product).getId();

        ProductVariant variant = new ProductVariant();
        variant.setProduct(product);
        variant.setQuantity(1.0);
        variant.setUnit("pc");
        variant.setMrp(new BigDecimal("100.00"));
        variant.setSellingPrice(SELLING_PRICE);
        variant.setAvailable(Boolean.TRUE);
        variant.setActive(Boolean.TRUE);
        variantId = variants.save(variant).getId();

        Inventory stock = new Inventory();
        stock.setProductVariant(variant);
        stock.setStock(50);
        inventories.save(stock);

        // Through the real endpoint, so the basket is exactly the one a phone
        // would have built.
        MvcResult added = mockMvc.perform(post("/api/carts/add")
                        .param("variantId", String.valueOf(variantId))
                        .param("quantity", "1")
                        .with(authentication(asCustomer())))
                .andReturn();
        assertEquals(200, added.getResponse().getStatus(),
                "the fixture basket was not built: " + added.getResponse().getContentAsString());
    }

    @AfterEach
    void tidyUp() {
        SecurityContextHolder.clearContext();
        jdbc.update("DELETE FROM idempotency_records WHERE customer_id = ?", customerId);
        // EVERY TABLE WITH A FOREIGN KEY TO orders, listed rather than
        // guessed: a checkout writes an invoice and a notification as well as
        // the obvious items and payment, and a missing one turns a passing
        // assertion into a teardown error that looks like a product bug.
        jdbc.update("DELETE FROM order_return_items WHERE order_return_id IN "
                + "(SELECT r.id FROM order_returns r JOIN orders o ON o.id = r.order_id "
                + "WHERE o.customer_id = ?)", customerId);
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

    @Test
    @DisplayName("a body claiming the order is already PAID is ignored")
    void aBodyClaimingPaidIsIgnored() throws Exception {
        // Every spelling somebody might reasonably have bound, in one body.
        // A DTO that accepted ANY of them would fail this test.
        String hostile = """
                {"addressId": %d,
                 "paymentMethod": "COD",
                 "paymentStatus": "PAID",
                 "payment_status": "PAID",
                 "status": "PAID",
                 "orderStatus": "DELIVERED",
                 "paid": true,
                 "isPaid": true}
                """.formatted(addressId);

        MvcResult placed = mockMvc.perform(post("/api/orders/place")
                        .header("Idempotency-Key", java.util.UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(hostile)
                        .with(authentication(asCustomer())))
                .andReturn();
        assertEquals(200, placed.getResponse().getStatus(),
                "the checkout itself must still succeed - the point is that the CLAIM is "
                        + "dropped, not that a hostile field breaks ordering: "
                        + placed.getResponse().getContentAsString());

        Order order = onlyOrder();

        // SUCCESS is what this codebase calls money-in; there is no PAID
        // constant. Both the "arrived" states are excluded so a rename cannot
        // make the assertion vacuous.
        assertNotEquals(PaymentStatus.SUCCESS, order.getPaymentStatus(),
                "A CUSTOMER DECLARED THEIR OWN ORDER PAID. The shop would pack and deliver "
                        + "groceries nobody was ever charged for, and the order would look "
                        + "identical to a real prepaid one on every screen.");
        assertNotEquals(PaymentStatus.COD_RECEIVED, order.getPaymentStatus(),
                "A CUSTOMER DECLARED THEIR OWN COD COLLECTED");
        assertEquals(PaymentStatus.COD_PENDING, order.getPaymentStatus(),
                "a COD checkout owes money until a rider collects it");
    }

    @Test
    @DisplayName("a body claiming its own total is ignored - the server prices the basket")
    void aBodyClaimingItsOwnTotalIsIgnored() throws Exception {
        String hostile = """
                {"addressId": %d,
                 "paymentMethod": "COD",
                 "totalAmount": 1,
                 "total": 1,
                 "finalAmount": 1,
                 "discountAmount": 9999,
                 "deliveryFee": 0,
                 "customerId": 999999999}
                """.formatted(addressId);

        MvcResult placed = mockMvc.perform(post("/api/orders/place")
                        .header("Idempotency-Key", java.util.UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(hostile)
                        .with(authentication(asCustomer())))
                .andReturn();
        assertEquals(200, placed.getResponse().getStatus(),
                placed.getResponse().getContentAsString());

        Order order = onlyOrder();

        // The one line in the basket costs 90, so any total at or below the
        // claimed 1 means the body was believed. Asserted as "at least the
        // item price" rather than as an exact figure because delivery fees and
        // taxes are settings a shop changes, and pinning them here would make
        // this security test fail for a pricing change.
        assertTrue(order.getTotalAmount().compareTo(SELLING_PRICE) >= 0,
                "A CUSTOMER PRICED THEIR OWN ORDER. The basket holds one item at "
                        + SELLING_PRICE + " and the order was written as "
                        + order.getTotalAmount());

        assertEquals(customerId, order.getCustomer().getId(),
                "the order must belong to the authenticated caller, never to a customerId "
                        + "supplied in the body");
    }

    @Test
    @DisplayName("a customer cannot confirm their own UPI payment")
    void aCustomerCannotConfirmTheirOwnUpiPayment() throws Exception {
        MvcResult placed = mockMvc.perform(post("/api/orders/place")
                        .header("Idempotency-Key", java.util.UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"addressId\": %d, \"paymentMethod\": \"COD\"}".formatted(addressId))
                        .with(authentication(asCustomer())))
                .andReturn();
        assertEquals(200, placed.getResponse().getStatus(),
                placed.getResponse().getContentAsString());
        Long orderId = onlyOrder().getId();

        // THE OTHER HALF OF THE SAME ATTACK. Having failed to declare the
        // order paid at checkout, the obvious next try is the route that
        // exists to declare payments arrived. It is gated on PAYMENTS_MANAGE,
        // which a customer does not hold - so this must be a refusal and not,
        // for instance, a 404 that would mean the gate had been moved and the
        // route merely renamed.
        assertEquals(403, mockMvc.perform(put("/api/payments/order/" + orderId + "/upi/confirm")
                                .param("transactionId", "made-up-by-the-buyer")
                                .with(authentication(asCustomer())))
                        .andReturn().getResponse().getStatus(),
                "a customer confirmed their own UPI payment, which marks the order paid "
                        + "without any money moving");

        assertEquals(403, mockMvc.perform(put("/api/payments/order/" + orderId + "/cod/complete")
                                .with(authentication(asCustomer())))
                        .andReturn().getResponse().getStatus(),
                "a customer settled their own COD, so the shop is owed cash nobody collected");

        assertNotEquals(PaymentStatus.SUCCESS, onlyOrder().getPaymentStatus());
        assertNotEquals(PaymentStatus.COD_RECEIVED, onlyOrder().getPaymentStatus(),
                "the refusals returned 403 but the money was marked collected anyway");
    }

    // ------------------------------------------------------------- fixtures

    private Order onlyOrder() {
        List<Long> ids = jdbc.queryForList(
                "SELECT id FROM orders WHERE customer_id = ? ORDER BY id", Long.class, customerId);
        assertEquals(1, ids.size(), "expected exactly one order for the fixture customer");
        return orders.findById(ids.get(0)).orElseThrow();
    }

    /** Exactly the principal and authorities JwtFilter builds for a real token. */
    private UsernamePasswordAuthenticationToken asCustomer() {
        List<GrantedAuthority> authorities = new ArrayList<>();
        for (String authority : RolePermissions.authorityNames(Role.CUSTOMER)) {
            authorities.add(new SimpleGrantedAuthority(authority));
        }
        AuthenticatedUser principal =
                new AuthenticatedUser(customerId, tag + "@example.test", Role.CUSTOMER.name());
        return new UsernamePasswordAuthenticationToken(principal, null, authorities);
    }
}
