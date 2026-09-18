package com.gpstore.platform;

import com.gpstore.entity.Role;
import com.gpstore.security.AuthenticatedUser;
import com.gpstore.security.RolePermissions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;

/**
 * A shopkeeper's customer is somebody who bought from them.
 *
 * <p>WHAT THE ENDPOINT SWEEP FOUND. Five routes took a person out of a URL - by
 * id, by email, by phone number - looked them up with {@code findById}, and
 * answered whoever held CUSTOMERS_VIEW or CUSTOMERS_MANAGE. {@code Role.ADMIN}
 * carries both, and every shop owner on GP-STORE is {@code Role.ADMIN}. Customer
 * is deliberately not a {@code ShopOwned} entity - a shopper belongs to the
 * platform, not to a shop - so nothing narrowed any of them:
 *
 * <ul>
 *   <li>{@code GET /api/customers/{id}/detail} - name, phone, every saved
 *       address, the contents of their basket, what they have spent.</li>
 *   <li>{@code GET /api/customers/email/{email}} and
 *       {@code /mobile/{mobileNumber}} - a lookup service over every account on
 *       the platform, which is also how you find out whether a given phone
 *       number is registered at all.</li>
 *   <li>{@code GET /api/carts} - every live basket on the marketplace, each
 *       line naming the product, the price and the shop it came off.</li>
 *   <li>{@code PUT /api/customers/{id}/active} - and this one is a WRITE. It
 *       deactivates the account and revokes every refresh token it holds, so
 *       any shop owner could sign any named person out of GP-STORE and keep
 *       them out: a rival's regulars, a rival's owner login, a platform
 *       administrator's own account.</li>
 * </ul>
 *
 * <p>THE DEFINITION IS THE FIX. A shop's customer is somebody who has ordered
 * from it - the same definition its customer list and its announcements already
 * use, and one the tenant filter answers for free because Order is shop-owned.
 * No new column, no new table, and no category anywhere near it.
 */
@SpringBootTest(properties = {
        // No platform.mode: the configuration production boots with.
        "outbox.initial-delay-ms=3600000",
        "outbox.drain-interval-ms=3600000",
        "payment.expiry-initial-delay-ms=3600000",
        "idempotency.cleanup-initial-delay-ms=3600000",
        "otp.cleanup-initial-delay-ms=3600000",
        "delivery.late-flag-initial-delay-ms=3600000"
})
@AutoConfigureMockMvc
@DisplayName("A merchant reads and changes only their own customers")
class AMerchantActsOnlyOnTheirOwnCustomersTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ShopRepository shops;
    @Autowired private PlatformProperties platform;
    @Autowired private MerchantLifecycleService merchantLifecycle;
    @Autowired private ShopLifecycleService shopLifecycle;

    private final String tag = "own" + System.nanoTime();

    private Long merchantA;
    private Long merchantB;
    private long shopA;
    private long shopB;
    private Long ownerA;
    private Long ownerB;
    private Long platformAdmin;
    private Long categoryId;
    private Long productA;
    private Long productB;
    private Long customerA;
    private Long customerB;
    private String emailB;
    private String mobileB;
    private String mobileA;

    @BeforeEach
    void twoShopsWithACustomerEach() {
        TenantDefaults.install(platform.getMode(),
                () -> shops.findByCode(platform.getFirstShopCode()).orElseThrow().getId());

        merchantA = newMerchant("a");
        merchantB = newMerchant("b");
        shopA = newShop(merchantA, "OWA-" + tag);
        shopB = newShop(merchantB, "OWB-" + tag);
        ownerA = newStaffFor(shopA, "a");
        ownerB = newStaffFor(shopB, "b");
        platformAdmin = newAccount("Platform " + tag, tag + "-platform@example.test",
                "SUPER_ADMIN");

        categoryId = newCategory("Own Cat " + tag);
        productA = newListedProduct(shopA, categoryId, "Own Product A " + tag);
        productB = newListedProduct(shopB, categoryId, "Own Product B " + tag);

        customerA = newAccount("Own Buyer A " + tag, tag + "-buyera@example.test");
        mobileA = mobileOf(customerA);
        newOrder(shopA, customerA, "OWA-ORD-" + tag);

        emailB = tag + "-buyerb@example.test";
        customerB = newAccount("Own Buyer B " + tag, emailB);
        mobileB = mobileOf(customerB);
        newOrder(shopB, customerB, "OWB-ORD-" + tag);
        jdbc.update("INSERT INTO addresses (customer_id, house_no, area, city, "
                        + "default_address) VALUES (?, ?, 'Ownville', 'Bengaluru', true)",
                customerB, "Own Lane " + tag);

        // A basket each, so "whose lines are these" has a real answer - and
        // customer A's basket ALSO holds a line from shop B, because a basket
        // spanning shops is the normal case on a marketplace and is precisely
        // what shop A must not be shown.
        basketFor(customerA, shopA, productA);
        addLine(customerA, shopB, productB);
        basketFor(customerB, shopB, productB);

        // The same shape for saved items: shop A's customer has one of shop
        // B's products on their wishlist.
        wishlistFor(customerA, productA);
        wishlistFor(customerA, productB);
    }

    @AfterEach
    void tidyUp() {
        jdbc.update("DELETE FROM wishlist WHERE customer_id IN (?, ?)", customerA, customerB);
        jdbc.update("DELETE FROM cart_items WHERE cart_id IN "
                + "(SELECT id FROM carts WHERE customer_id IN (?, ?))", customerA, customerB);
        jdbc.update("DELETE FROM carts WHERE customer_id IN (?, ?)", customerA, customerB);
        jdbc.update("DELETE FROM orders WHERE shop_id IN (?, ?)", shopA, shopB);
        jdbc.update("DELETE FROM addresses WHERE customer_id IN (?, ?)", customerA, customerB);
        for (long shop : new long[]{shopA, shopB}) {
            jdbc.update("DELETE FROM inventory WHERE shop_id = ?", shop);
            jdbc.update("DELETE FROM shop_product_variants WHERE shop_id = ?", shop);
            jdbc.update("DELETE FROM shop_business_hours WHERE shop_id = ?", shop);
            jdbc.update("DELETE FROM shop_staff WHERE shop_id = ?", shop);
            jdbc.update("DELETE FROM delivery_pricing_settings WHERE shop_id = ?", shop);
            jdbc.update("DELETE FROM store_operations_settings WHERE shop_id = ?", shop);
            jdbc.update("DELETE FROM shops WHERE id = ?", shop);
        }
        for (Long product : new Long[]{productA, productB}) {
            jdbc.update("DELETE FROM product_variants WHERE product_id = ?", product);
            jdbc.update("DELETE FROM products WHERE id = ?", product);
        }
        jdbc.update("DELETE FROM categories WHERE id = ?", categoryId);
        jdbc.update("DELETE FROM merchants WHERE id in (?, ?)", merchantA, merchantB);
        jdbc.update("DELETE FROM customers WHERE id in (?, ?, ?, ?, ?)",
                ownerA, ownerB, customerA, customerB, platformAdmin);
        TenantDefaults.install(platform.getMode(),
                () -> shops.findByCode(platform.getFirstShopCode()).orElseThrow().getId());
    }

    // ------------------------------------------------------------------

    @Nested
    @DisplayName("looking somebody up")
    class TheLookup {

        @Test
        @DisplayName("a merchant finds their own customer by phone number")
        void findsTheirOwn() throws Exception {
            // THE FEATURE MUST SURVIVE THE FIX. A shopkeeper taking a phone
            // order looks the caller up by the number they rang from.
            MvcResult result = send(get("/api/customers/mobile/" + mobileA), ownerA);

            assertEquals(200, result.getResponse().getStatus());
            assertTrue(result.getResponse().getContentAsString().contains("Own Buyer A " + tag),
                    "a merchant could not find their OWN customer by phone number: "
                            + result.getResponse().getContentAsString());
        }

        @Test
        @DisplayName("a merchant does not find another shop's customer by phone number")
        void doesNotFindAStranger() throws Exception {
            MvcResult result = send(get("/api/customers/mobile/" + mobileB), ownerA);
            String body = result.getResponse().getContentAsString();

            assertFalse(body.contains("Own Buyer B " + tag) || body.contains(emailB),
                    "GET /api/customers/mobile/{n} answered " + result.getResponse().getStatus()
                            + " with another shop's customer. Any merchant could type any "
                            + "phone number and read back the account on it. Body: " + body);
        }

        @Test
        @DisplayName("a merchant does not find another shop's customer by email")
        void doesNotFindAStrangerByEmail() throws Exception {
            MvcResult result = send(get("/api/customers/email/" + emailB), ownerA);
            String body = result.getResponse().getContentAsString();

            assertFalse(body.contains("Own Buyer B " + tag) || body.contains(mobileB),
                    "GET /api/customers/email/{e} answered " + result.getResponse().getStatus()
                            + " with another shop's customer: " + body);
        }

        @Test
        @DisplayName("the platform still looks up anybody")
        void thePlatformLooksUpAnybody() throws Exception {
            MvcResult result = send(get("/api/customers/email/" + emailB), platformAdmin,
                    Role.SUPER_ADMIN);

            assertEquals(200, result.getResponse().getStatus());
            assertTrue(result.getResponse().getContentAsString().contains("Own Buyer B " + tag),
                    "the platform console lost a lookup it is supposed to have: "
                            + result.getResponse().getContentAsString());
        }
    }

    @Nested
    @DisplayName("the file on one person")
    class TheDossier {

        @Test
        @DisplayName("a merchant reads the file on their own customer")
        void readsTheirOwn() throws Exception {
            MvcResult result = send(get("/api/customers/" + customerA + "/detail"), ownerA);

            assertEquals(200, result.getResponse().getStatus(),
                    "a merchant lost the customer screen for their OWN customer: "
                            + result.getResponse().getContentAsString());
            String body = result.getResponse().getContentAsString();
            assertTrue(body.contains("Own Product A " + tag),
                    "their own customer's basket lost the merchant's own line: " + body);
            // THE SAME SHOPPER, THE RIVAL'S HALF OF THEIR BASKET. A basket
            // spans shops by design; the file this shop reads is their own
            // lines and their own saved items, not a report on where else
            // their customer shops and at what price.
            assertFalse(body.contains("Own Product B " + tag),
                    "the customer screen showed this merchant what their customer is "
                            + "buying from another shop: " + body);
        }

        @Test
        @DisplayName("a merchant does not read the file on another shop's customer")
        void doesNotReadAStrangers() throws Exception {
            MvcResult result = send(get("/api/customers/" + customerB + "/detail"), ownerA);
            String body = result.getResponse().getContentAsString();

            assertEquals(404, result.getResponse().getStatus(),
                    "GET /api/customers/{id}/detail handed one merchant another merchant's "
                            + "customer - name, phone, every saved address and their basket. "
                            + "Body: " + body);
            assertFalse(body.contains("Own Lane " + tag),
                    "a stranger's home address came back: " + body);
        }
    }

    @Nested
    @DisplayName("the abandoned-basket list")
    class TheBaskets {

        @Test
        @DisplayName("a merchant sees their own lines and not another shop's")
        void seesOwnLinesOnly() throws Exception {
            MvcResult result = send(get("/api/carts?page=0&size=100"), ownerA);
            String body = result.getResponse().getContentAsString();

            assertEquals(200, result.getResponse().getStatus(), body);
            assertTrue(body.contains("Own Product A " + tag),
                    "the merchant's own abandoned basket vanished from GET /api/carts. "
                            + "Narrowed is not the same as emptied. Body: " + body);
            assertFalse(body.contains("Own Product B " + tag),
                    "GET /api/carts handed one merchant another shop's live baskets - "
                            + "what a shopper is buying elsewhere, at what price. Body: " + body);
        }

        @Test
        @DisplayName("reading one basket answers with the caller's lines, not a 500")
        void oneBasketReadsCleanly() throws Exception {
            Long cartB = jdbc.queryForObject(
                    "SELECT id FROM carts WHERE customer_id = ?", Long.class, customerB);

            MvcResult result = send(get("/api/cart-items/cart/" + cartB), ownerA);
            String body = result.getResponse().getContentAsString();

            // The route used to return raw entities and answer 500 to
            // everybody - see CartItemController. Support needs the read
            // (CartsAreNotWritableByAReadRoleTest), so it is fixed rather
            // than removed, and narrowed while it is being fixed.
            assertEquals(200, result.getResponse().getStatus(),
                    "reading a basket crashed instead of answering: " + body);
            assertFalse(body.contains("Own Product B " + tag),
                    "another shop's basket line came back: " + body);
        }

        @Test
        @DisplayName("clearing a basket removes the caller's lines and leaves the rest")
        void clearingTakesOnlyOwnLines() throws Exception {
            Long cartA = jdbc.queryForObject(
                    "SELECT id FROM carts WHERE customer_id = ?", Long.class, customerA);

            MvcResult result = send(
                    delete("/api/cart-items/cart/" + cartA), ownerA);

            assertTrue(result.getResponse().getStatus() < 400,
                    "a merchant could not clear their own lines: "
                            + result.getResponse().getContentAsString());
            assertEquals(0, linesFrom(cartA, shopA), "the merchant's own line survived");
            assertEquals(1, linesFrom(cartA, shopB),
                    "DELETE /api/cart-items/cart/{id} emptied the whole basket, including "
                            + "lines another shop had sold into it. The shopper reaches "
                            + "checkout and the rival's item has silently vanished.");
        }

        @Test
        @DisplayName("a merchant may not delete another shop's basket line")
        void cannotDeleteAnotherShopsLine() throws Exception {
            Long cartA = jdbc.queryForObject(
                    "SELECT id FROM carts WHERE customer_id = ?", Long.class, customerA);
            Long lineFromB = jdbc.queryForObject(
                    "SELECT id FROM cart_items WHERE cart_id = ? AND shop_id = ?",
                    Long.class, cartA, shopB);

            MvcResult result = send(delete("/api/cart-items/" + lineFromB), ownerA);

            assertEquals(404, result.getResponse().getStatus(),
                    "DELETE /api/cart-items/{id} let one merchant delete a line another "
                            + "merchant had sold: " + result.getResponse().getContentAsString());
            assertEquals(1, linesFrom(cartA, shopB), "the other shop's line was deleted anyway");
        }

        @Test
        @DisplayName("a merchant may delete their own basket line")
        void deletesOwnLine() throws Exception {
            Long cartA = jdbc.queryForObject(
                    "SELECT id FROM carts WHERE customer_id = ?", Long.class, customerA);
            Long lineFromA = jdbc.queryForObject(
                    "SELECT id FROM cart_items WHERE cart_id = ? AND shop_id = ?",
                    Long.class, cartA, shopA);

            MvcResult result = send(delete("/api/cart-items/" + lineFromA), ownerA);

            assertTrue(result.getResponse().getStatus() < 400,
                    "a merchant lost the ability to remove their OWN line: "
                            + result.getResponse().getContentAsString());
            assertEquals(0, linesFrom(cartA, shopA));
            // The stored totals are denormalised, so a partial removal has to
            // re-derive them or the basket badge keeps counting a line that is
            // no longer there.
            assertEquals(1, jdbc.queryForObject(
                    "SELECT total_items FROM carts WHERE id = ?", Integer.class, cartA),
                    "the basket's stored total still counts the removed line");
        }

        private int linesFrom(Long cartId, long shop) {
            return jdbc.queryForObject(
                    "SELECT count(*) FROM cart_items WHERE cart_id = ? AND shop_id = ?",
                    Integer.class, cartId, shop);
        }
    }

    @Nested
    @DisplayName("switching an account off")
    class TheBan {

        @Test
        @DisplayName("a merchant may bar their own customer")
        void barsTheirOwn() throws Exception {
            MvcResult result = send(
                    put("/api/customers/" + customerA + "/active?active=false"), ownerA);

            assertEquals(200, result.getResponse().getStatus(),
                    "a merchant lost the ability to bar their OWN customer: "
                            + result.getResponse().getContentAsString());
            assertEquals(Boolean.FALSE, jdbc.queryForObject(
                    "SELECT active FROM customers WHERE id = ?", Boolean.class, customerA));
        }

        @Test
        @DisplayName("a merchant may not bar another shop's customer")
        void cannotBarAStranger() throws Exception {
            MvcResult result = send(
                    put("/api/customers/" + customerB + "/active?active=false"), ownerA);

            assertEquals(404, result.getResponse().getStatus(),
                    "PUT /api/customers/{id}/active let one merchant deactivate another "
                            + "shop's customer. It also revokes every refresh token the "
                            + "account holds, so this signs a stranger out of GP-STORE and "
                            + "keeps them out. Body: " + result.getResponse().getContentAsString());
            assertEquals(Boolean.TRUE, jdbc.queryForObject(
                    "SELECT active FROM customers WHERE id = ?", Boolean.class, customerB),
                    "another shop's customer was deactivated anyway");
        }

        @Test
        @DisplayName("a merchant may not bar another merchant's owner account")
        void cannotBarAColleague() throws Exception {
            // ownerB has never ordered from shop A, and holds a staff role -
            // either rule alone refuses this. Both are here because the first
            // stops being enough the moment a merchant buys their own groceries
            // from a rival: an account that HAS ordered from shop A is still not
            // shop A's to disable if it is somebody's staff login.
            MvcResult result = send(
                    put("/api/customers/" + ownerB + "/active?active=false"), ownerA);

            assertEquals(404, result.getResponse().getStatus(),
                    "one merchant deactivated another merchant's owner login: "
                            + result.getResponse().getContentAsString());
            assertEquals(Boolean.TRUE, jdbc.queryForObject(
                    "SELECT active FROM customers WHERE id = ?", Boolean.class, ownerB));
        }

        @Test
        @DisplayName("a merchant may not bar a staff account that did buy from them")
        void cannotBarAColleagueWhoShops() throws Exception {
            // ownerB now IS a customer of shop A. The shop rule is satisfied;
            // the staff rule is what refuses this, and it must.
            newOrder(shopA, ownerB, "OWA-ORD2-" + tag);

            MvcResult result = send(
                    put("/api/customers/" + ownerB + "/active?active=false"), ownerA);

            assertEquals(404, result.getResponse().getStatus(),
                    "a merchant disabled another merchant's owner login by first selling "
                            + "them something: " + result.getResponse().getContentAsString());
            assertEquals(Boolean.TRUE, jdbc.queryForObject(
                    "SELECT active FROM customers WHERE id = ?", Boolean.class, ownerB));
        }

        @Test
        @DisplayName("the platform still bars anybody")
        void thePlatformBarsAnybody() throws Exception {
            MvcResult result = send(
                    put("/api/customers/" + customerB + "/active?active=false"),
                    platformAdmin, Role.SUPER_ADMIN);

            assertEquals(200, result.getResponse().getStatus(),
                    "the platform lost an action it is supposed to have: "
                            + result.getResponse().getContentAsString());
            assertEquals(Boolean.FALSE, jdbc.queryForObject(
                    "SELECT active FROM customers WHERE id = ?", Boolean.class, customerB));
        }
    }

    // ------------------------------------------------------------------

    private Long newMerchant(String kind) {
        Long id = merchantLifecycle.register(
                "Own Merchant " + kind + " " + tag, "Own Shop",
                null, null, null, true).getId();
        merchantLifecycle.transition(id, MerchantStatus.PENDING_REVIEW, "submitted");
        merchantLifecycle.transition(id, MerchantStatus.APPROVED, "checked");
        merchantLifecycle.transition(id, MerchantStatus.ACTIVE, "trading");
        return id;
    }

    private long newShop(Long merchant, String code) {
        long id = shopLifecycle.open(merchant, code, "Own Shop " + code,
                12.9716, 77.5946, new java.math.BigDecimal("5"), "Asia/Kolkata").getId();
        shopLifecycle.transitionAsPlatform(id, ShopStatus.ACTIVE, "ready to trade");
        return id;
    }

    private Long newStaffFor(long shop, String kind) {
        Long id = newAccount("Own Owner " + kind + " " + tag,
                tag + "-" + kind + "@example.test", "ADMIN");
        jdbc.update("INSERT INTO shop_staff (shop_id, customer_id, is_default, active) "
                + "VALUES (?, ?, true, true)", shop, id);
        return id;
    }

    /** A shopper: a plain CUSTOMER account, which is what a shopper is. */
    private Long newAccount(String name, String email) {
        return newAccount(name, email, "CUSTOMER");
    }

    private Long newAccount(String name, String email, String role) {
        jdbc.update("INSERT INTO customers (full_name, email, mobile_number, password, "
                        + "role, active) VALUES (?, ?, ?, 'not-a-real-hash', ?, true)",
                name, email, "9" + (100000000 + (int) (Math.random() * 899999999)), role);
        return jdbc.queryForObject("SELECT id FROM customers WHERE email = ?", Long.class, email);
    }

    private String mobileOf(Long customerId) {
        return jdbc.queryForObject(
                "SELECT mobile_number FROM customers WHERE id = ?", String.class, customerId);
    }

    private Long newCategory(String name) {
        jdbc.update("INSERT INTO categories (name, description, active) VALUES (?, ?, true)",
                name, name + " department");
        return jdbc.queryForObject("SELECT id FROM categories WHERE name = ?", Long.class, name);
    }

    private Long newListedProduct(long shop, Long category, String name) {
        jdbc.update("INSERT INTO products (name, brand, category_id, active) "
                + "VALUES (?, 'Own', ?, true)", name, category);
        Long productId = jdbc.queryForObject(
                "SELECT id FROM products WHERE name = ?", Long.class, name);
        jdbc.update("INSERT INTO product_variants "
                + "(product_id, unit, selling_price, mrp, available, active) "
                + "VALUES (?, 'one', 100, 120, true, true)", productId);
        Long v = jdbc.queryForObject(
                "SELECT id FROM product_variants WHERE product_id = ?", Long.class, productId);
        jdbc.update("INSERT INTO shop_product_variants "
                + "(shop_id, product_variant_id, selling_price, mrp, available, active) "
                + "VALUES (?, ?, 100, 120, true, true)", shop, v);
        return productId;
    }

    private void wishlistFor(Long customerId, Long productId) {
        jdbc.update("INSERT INTO wishlist (customer_id, product_id, active) VALUES (?, ?, true)",
                customerId, productId);
    }

    private void addLine(Long customerId, long shop, Long productId) {
        Long cart = jdbc.queryForObject(
                "SELECT id FROM carts WHERE customer_id = ?", Long.class, customerId);
        Long variant = jdbc.queryForObject(
                "SELECT id FROM product_variants WHERE product_id = ?", Long.class, productId);
        jdbc.update("INSERT INTO cart_items (cart_id, product_variant_id, shop_id, "
                + "quantity, price, total_price) VALUES (?, ?, ?, 1, 100, 100)",
                cart, variant, shop);
    }

    private void basketFor(Long customerId, long shop, Long productId) {
        jdbc.update("INSERT INTO carts (customer_id, total_amount, total_items) "
                + "VALUES (?, 100, 1)", customerId);
        Long cart = jdbc.queryForObject(
                "SELECT id FROM carts WHERE customer_id = ?", Long.class, customerId);
        Long variant = jdbc.queryForObject(
                "SELECT id FROM product_variants WHERE product_id = ?", Long.class, productId);
        jdbc.update("INSERT INTO cart_items (cart_id, product_variant_id, shop_id, "
                + "quantity, price, total_price) VALUES (?, ?, ?, 1, 100, 100)",
                cart, variant, shop);
    }

    private void newOrder(long shop, Long customerId, String orderNumber) {
        jdbc.update("INSERT INTO orders (shop_id, customer_id, order_number, order_status, "
                        + "total_amount, order_date) VALUES (?, ?, ?, 'DELIVERED', 100, now())",
                shop, customerId, orderNumber);
    }

    private MvcResult send(MockHttpServletRequestBuilder request, Long accountId)
            throws Exception {
        return send(request, accountId, Role.ADMIN);
    }

    private MvcResult send(MockHttpServletRequestBuilder request, Long accountId, Role role)
            throws Exception {
        request.with(authentication(tokenFor(accountId, role)));
        return mockMvc.perform(request).andReturn();
    }

    private UsernamePasswordAuthenticationToken tokenFor(Long accountId, Role role) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        for (String authority : RolePermissions.authorityNames(role)) {
            authorities.add(new SimpleGrantedAuthority(authority));
        }
        return new UsernamePasswordAuthenticationToken(
                new AuthenticatedUser(accountId, tag + "@example.test", role.name()),
                null, authorities);
    }
}
