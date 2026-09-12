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
import org.springframework.http.MediaType;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;

/**
 * Putting stock behind a listing, through the API a real shopkeeper has.
 *
 * <p>WHY THIS TEST EXISTS. Onboarding a second merchant looked complete and
 * was not. A shop could set its price ({@code PUT /api/shop/listings/{id}})
 * and had no route at all for the other half - how much of it there is.
 * {@code /api/inventory} wants a whole {@code Inventory} entity and the stock
 * row's own id, which a listing created a second ago has not got. The
 * onboarding test papered over it with {@code jdbc.update("INSERT INTO
 * inventory ...")}, which made the journey pass and would have left a real
 * merchant unable to open their shelf.
 *
 * <p>THE PROPERTY THAT MATTERS MOST IS THE THIRD GROUP. Two shops selling the
 * same sack of atta is the ordinary case in a marketplace, not an edge one.
 * Stock is unique on (shop_id, product_variant_id) and not on the variant
 * alone (V48), and the test below is what says so from outside the database:
 * Shop B counting its own stock must not move Shop A's number.
 */
@SpringBootTest(properties = {
        // WITHOUT THIS THE TEST PASSES BY ACCIDENT AND PROVES NOTHING. In
        // single-shop mode TenantResolver short-circuits to the one shop and
        // no filter is applied, so both owners below resolve to Shop 1 and
        // "Shop B's stock" is Shop A's row under another name. The first run
        // of this test did exactly that: readiness answered shopId 1 for Shop
        // B's owner and reported 589 riders - the whole platform's roster.
        "platform.mode=MULTI_SHOP_PRODUCTION",
        "outbox.initial-delay-ms=3600000",
        "outbox.drain-interval-ms=3600000",
        "payment.expiry-initial-delay-ms=3600000",
        "idempotency.cleanup-initial-delay-ms=3600000",
        "otp.cleanup-initial-delay-ms=3600000",
        "delivery.late-flag-initial-delay-ms=3600000"
})
@AutoConfigureMockMvc
@DisplayName("A shop stocks its own shelf")
class AShopStocksItsOwnShelfTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ShopRepository shops;
    @Autowired private PlatformProperties platform;
    @Autowired private MerchantLifecycleService merchantLifecycle;
    @Autowired private ShopLifecycleService shopLifecycle;

    private final String tag = "stk" + System.nanoTime();

    private long shopA;
    private long shopB;
    private Long merchantA;
    private Long merchantB;
    private Long ownerA;
    private Long ownerB;
    private Long variantId;

    /**
     * TWO SHOPS THIS TEST MADE, AND SHOP #1 IS NOT ONE OF THEM.
     *
     * <p>THIS USED TO USE SHOP #1 AS "SHOP A" and it was a bad idea that did
     * real damage. Every delete in the teardown was keyed on {@code shop_id},
     * so tearing down took out EVERY staff row, listing and stock row
     * belonging to shop #1 - rows this test never created and had no business
     * touching. It emptied the first shop in the shared test database, and a
     * fixture that deletes by tenant rather than by what it inserted would do
     * exactly the same to any database it was pointed at.
     *
     * <p>The isolation claim never needed shop #1. "Two shops cannot see each
     * other's stock" is proved by any two shops; using the live one bought
     * nothing and risked everything. Shop #1 is now never read and never
     * written here.
     */
    @BeforeEach
    void twoShopsAndOneCatalogueItem() {
        TenantDefaults.install(PlatformMode.MULTI_SHOP_PRODUCTION,
                () -> shops.findByCode(platform.getFirstShopCode()).orElseThrow().getId());

        // THROUGH THE REAL LIFECYCLE SERVICES, not raw INSERTs. The fixture
        // used to hand-write the merchant and shop rows and it broke the first
        // time it met a genuinely fresh database: merchants.active is NOT NULL
        // with no default, so an INSERT naming only the columns that seemed
        // interesting failed. A fixture assembled out of hand-written SQL has
        // to be re-taught the schema every time a column gains a constraint;
        // one built from the services it is testing around never does.
        merchantA = newMerchant("a");
        merchantB = newMerchant("b");
        shopA = newShop(merchantA, "STKA-" + tag);
        shopB = newShop(merchantB, "STKB-" + tag);

        ownerA = newStaffFor(shopA, "a");
        ownerB = newStaffFor(shopB, "b");

        variantId = jdbc.queryForObject(
                "SELECT id FROM product_variants ORDER BY id ASC LIMIT 1", Long.class);
        assertNotNull(variantId, "the shared catalogue has no variant to list");
    }

    @AfterEach
    void tidyUp() {
        // Keyed on the two shops THIS TEST CREATED, which is safe precisely
        // because neither of them is anybody else's.
        for (long shop : new long[]{shopA, shopB}) {
            jdbc.update("DELETE FROM inventory WHERE shop_id = ?", shop);
            jdbc.update("DELETE FROM shop_product_variants WHERE shop_id = ?", shop);
            jdbc.update("DELETE FROM shop_business_hours WHERE shop_id = ?", shop);
            jdbc.update("DELETE FROM shop_staff WHERE shop_id = ?", shop);
            jdbc.update("DELETE FROM delivery_pricing_settings WHERE shop_id = ?", shop);
            jdbc.update("DELETE FROM store_operations_settings WHERE shop_id = ?", shop);
            jdbc.update("DELETE FROM shops WHERE id = ?", shop);
        }
        jdbc.update("DELETE FROM merchants WHERE id in (?, ?)", merchantA, merchantB);
        jdbc.update("DELETE FROM customers WHERE id in (?, ?)", ownerA, ownerB);
        TenantDefaults.install(PlatformMode.SINGLE_SHOP,
                () -> shops.findByCode(platform.getFirstShopCode()).orElseThrow().getId());
    }

    /** A merchant taken all the way to trading, the way the platform does it. */
    private Long newMerchant(String kind) {
        Long id = merchantLifecycle.register(
                "Stock Test Merchant " + kind + " " + tag, "Stock Test Shop",
                null, null, null, true).getId();
        merchantLifecycle.transition(id, MerchantStatus.PENDING_REVIEW, "submitted");
        merchantLifecycle.transition(id, MerchantStatus.APPROVED, "checked");
        merchantLifecycle.transition(id, MerchantStatus.ACTIVE, "trading");
        return id;
    }

    /** A shop opened under that merchant and switched on. */
    private long newShop(Long merchant, String code) {
        long id = shopLifecycle.open(merchant, code, "Stock Test Shop",
                12.9716, 77.5946, new java.math.BigDecimal("5"), "Asia/Kolkata").getId();
        shopLifecycle.transitionAsPlatform(id, ShopStatus.ACTIVE, "ready to trade");
        return id;
    }

    // ------------------------------------------------------------------

    @Nested
    @DisplayName("the route that was missing")
    class TheRoute {

        @Test
        @DisplayName("a shop lists an item and then says how much of it there is")
        void listThenStock() throws Exception {
            list(ownerA, 95.00);

            String stocked = body(put(stockPath()), ownerA,
                    "{\"stock\":50,\"minimumStock\":5}");

            assertTrue(stocked.contains("\"stock\":50"), stocked);
            assertTrue(stocked.contains("\"availableStock\":50"), stocked);
            assertEquals(50, stockRowFor(shopA),
                    "the API reported 50 but the shop's row does not say so");
        }

        @Test
        @DisplayName("stocking something this shop does not sell is refused")
        void stockWithoutListing() throws Exception {
            // No list(...) call: the shelf is empty.
            perform(put(stockPath()), ownerA, "{\"stock\":50}", 404);
            assertEquals(0, jdbc.queryForObject(
                    "SELECT count(*) FROM inventory WHERE shop_id = ? AND product_variant_id = ?",
                    Integer.class, shopA, variantId),
                    "a stock row was created for an item the shop does not list");
        }

        @Test
        @DisplayName("negative stock is refused")
        void negativeStock() throws Exception {
            list(ownerA, 95.00);
            perform(put(stockPath()), ownerA, "{\"stock\":-1}", 400);
        }

        @Test
        @DisplayName("a second call corrects the count rather than creating a second row")
        void correctionIsNotDuplication() throws Exception {
            list(ownerA, 95.00);
            perform(put(stockPath()), ownerA, "{\"stock\":50}", 200);
            perform(put(stockPath()), ownerA, "{\"stock\":12}", 200);

            assertEquals(1, jdbc.queryForObject(
                    "SELECT count(*) FROM inventory WHERE shop_id = ? AND product_variant_id = ?",
                    Integer.class, shopA, variantId),
                    "a stock-take created a second row instead of correcting the first");
            assertEquals(12, stockRowFor(shopA));
        }

        @Test
        @DisplayName("a stock-take does not release stock somebody has already paid for")
        void reservedStockSurvives() throws Exception {
            list(ownerA, 95.00);
            perform(put(stockPath()), ownerA, "{\"stock\":50}", 200);
            jdbc.update("UPDATE inventory SET reserved_stock = 8 "
                    + "WHERE shop_id = ? AND product_variant_id = ?", shopA, variantId);

            String corrected = body(put(stockPath()), ownerA, "{\"stock\":40}");

            assertEquals(8, jdbc.queryForObject(
                    "SELECT reserved_stock FROM inventory WHERE shop_id = ? "
                            + "AND product_variant_id = ?", Integer.class, shopA, variantId),
                    "counting the shelf released stock that baskets and orders hold");
            assertTrue(corrected.contains("\"availableStock\":32"),
                    "available stock must be what is there minus what is spoken for: " + corrected);
        }
    }

    @Nested
    @DisplayName("two shops, one catalogue item")
    class TwoShops {

        @Test
        @DisplayName("each shop's count is its own")
        void stockIsPerShop() throws Exception {
            list(ownerA, 95.00);
            list(ownerB, 88.00);

            perform(put(stockPath()), ownerA, "{\"stock\":50}", 200);
            perform(put(stockPath()), ownerB, "{\"stock\":7}", 200);

            assertEquals(50, stockRowFor(shopA), "Shop B's stock-take moved Shop A's number");
            assertEquals(7, stockRowFor(shopB));

            // ONE ROW EACH, COUNTED PER SHOP - not one count across the whole
            // table. The first version of this asserted that the variant had
            // exactly two inventory rows in total, which says nothing about
            // this test's two shops and everything about whoever else happens
            // to stock that catalogue item: it read 3 the moment another shop
            // in the database held the same item. Asserting over rows the test
            // does not own is the same mistake as deleting them.
            assertEquals(1, rowsFor(shopA), "Shop A must hold exactly one row for this item");
            assertEquals(1, rowsFor(shopB), "Shop B must hold exactly one row for this item");
            // And they are genuinely separate rows, which is the property that
            // matters: if inventory were unique on the variant alone, one of
            // these two writes could not have existed at all.
            assertNotEquals(
                    jdbc.queryForObject("SELECT id FROM inventory WHERE shop_id = ? "
                            + "AND product_variant_id = ?", Long.class, shopA, variantId),
                    jdbc.queryForObject("SELECT id FROM inventory WHERE shop_id = ? "
                            + "AND product_variant_id = ?", Long.class, shopB, variantId),
                    "both shops are pointing at the same stock row");
        }

        @Test
        @DisplayName("a shop reads back its own count and not its neighbour's")
        void readsAreScoped() throws Exception {
            list(ownerA, 95.00);
            list(ownerB, 88.00);
            perform(put(stockPath()), ownerA, "{\"stock\":50}", 200);
            perform(put(stockPath()), ownerB, "{\"stock\":7}", 200);

            assertTrue(body(get(stockPath()), ownerA, null).contains("\"stock\":50"));
            assertTrue(body(get(stockPath()), ownerB, null).contains("\"stock\":7"));
        }

        @Test
        @DisplayName("a shop that lists nothing cannot read the other's count")
        void theEmptyShelfSeesNothing() throws Exception {
            list(ownerA, 95.00);
            perform(put(stockPath()), ownerA, "{\"stock\":50}", 200);

            // Shop B has not listed the item, so for Shop B it does not exist -
            // and the refusal says "you do not list that", never "there are 50
            // of them next door".
            MvcResult peek = send(get(stockPath()), ownerB, null);
            assertEquals(404, peek.getResponse().getStatus());
            assertFalse(peek.getResponse().getContentAsString().contains("50"),
                    "the refusal leaked the other shop's count: "
                            + peek.getResponse().getContentAsString());
        }
    }

    @Nested
    @DisplayName("the onboarding checklist")
    class TheChecklist {

        @Test
        @DisplayName("tells a new shop it is trading on GP-STORE's hours, not its own")
        void hoursAreOnTheChecklist() throws Exception {
            String readiness = body(get("/api/shop/readiness"), ownerB, null);

            assertTrue(readiness.contains("\"name\":\"shop-hours\",\"done\":false"),
                    "a shop that has never set its week must be told it is running on the "
                            + "deployment's clock: " + readiness);
            assertTrue(readiness.contains("\"name\":\"shop-hours\",\"done\":false,"
                            + "\"blocking\":false"),
                    "the shop IS trading meanwhile, so this step must not claim to block "
                            + "orders: " + readiness);
        }

        @Test
        @DisplayName("stops saying it once the shop sets its own week")
        void hoursGoGreen() throws Exception {
            for (int day = 1; day <= 7; day++) {
                jdbc.update("""
                        INSERT INTO shop_business_hours (shop_id, day_of_week, opens_at, closes_at)
                        VALUES (?, ?, '08:00', '21:00')
                        """, shopB, day);
            }

            String readiness = body(get("/api/shop/readiness"), ownerB, null);
            assertTrue(readiness.contains("\"name\":\"shop-hours\",\"done\":true"), readiness);
        }

        @Test
        @DisplayName("a shop's own hours are not read from its neighbour's week")
        void hoursAreScoped() throws Exception {
            for (int day = 1; day <= 7; day++) {
                jdbc.update("""
                        INSERT INTO shop_business_hours (shop_id, day_of_week, opens_at, closes_at)
                        VALUES (?, ?, '08:00', '21:00')
                        """, shopB, day);
            }

            // Shop B has a full week. Shop A's checklist must not inherit it.
            // If this fails, the hours query is not shop-scoped and every shop
            // on the platform is reading one week.
            String shopAReadiness = body(get("/api/shop/readiness"), ownerA, null);
            long shopAOwnHours = jdbc.queryForObject(
                    "SELECT count(*) FROM shop_business_hours WHERE shop_id = ?",
                    Long.class, shopA);
            assertEquals(shopAOwnHours > 0,
                    shopAReadiness.contains("\"name\":\"shop-hours\",\"done\":true"),
                    "Shop A's checklist disagrees with Shop A's own hours rows, which means it "
                            + "is counting somebody else's: " + shopAReadiness);
        }
    }

    // ------------------------------------------------------------- plumbing

    private String stockPath() {
        return "/api/shop/listings/" + variantId + "/stock";
    }

    private void list(Long owner, double price) throws Exception {
        perform(put("/api/shop/listings/" + variantId), owner,
                "{\"sellingPrice\":%s,\"mrp\":%s,\"available\":true,\"active\":true}"
                        .formatted(price, price + 15), 200);
    }

    private Integer rowsFor(long shop) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM inventory WHERE shop_id = ? AND product_variant_id = ?",
                Integer.class, shop, variantId);
    }

    private Integer stockRowFor(long shop) {
        return jdbc.queryForObject(
                "SELECT stock FROM inventory WHERE shop_id = ? AND product_variant_id = ?",
                Integer.class, shop, variantId);
    }

    private Long newStaffFor(long shop, String kind) {
        String email = tag + "-" + kind + "@example.test";
        jdbc.update("""
                INSERT INTO customers (full_name, email, mobile_number, password, role, active)
                VALUES (?, ?, ?, 'not-a-real-hash', 'ADMIN', true)
                """, "Owner " + kind + " " + tag, email,
                "9" + (100000000 + (int) (Math.random() * 899999999)));
        Long id = jdbc.queryForObject(
                "SELECT id FROM customers WHERE email = ?", Long.class, email);
        jdbc.update("""
                INSERT INTO shop_staff (shop_id, customer_id, is_default, active)
                VALUES (?, ?, true, true)
                """, shop, id);
        return id;
    }

    private String body(MockHttpServletRequestBuilder request, Long accountId, String json)
            throws Exception {
        MvcResult result = send(request, accountId, json);
        int status = result.getResponse().getStatus();
        String content = result.getResponse().getContentAsString();
        assertTrue(status >= 200 && status < 300, request + " returned " + status + ": " + content);
        return content;
    }

    private void perform(MockHttpServletRequestBuilder request, Long accountId, String json,
                         int expected) throws Exception {
        MvcResult result = send(request, accountId, json);
        assertEquals(expected, result.getResponse().getStatus(),
                "unexpected status: " + result.getResponse().getContentAsString());
    }

    private MvcResult send(MockHttpServletRequestBuilder request, Long accountId, String json)
            throws Exception {
        request.with(authentication(tokenFor(accountId)));
        if (json != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        return mockMvc.perform(request).andReturn();
    }

    private UsernamePasswordAuthenticationToken tokenFor(Long accountId) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        for (String authority : RolePermissions.authorityNames(Role.ADMIN)) {
            authorities.add(new SimpleGrantedAuthority(authority));
        }
        return new UsernamePasswordAuthenticationToken(
                new AuthenticatedUser(accountId, tag + "@example.test", Role.ADMIN.name()),
                null, authorities);
    }
}
