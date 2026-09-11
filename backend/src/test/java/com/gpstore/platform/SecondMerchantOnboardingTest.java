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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * A second kirana joins the marketplace, and its first order goes out.
 *
 * EVERY OTHER SLICE PROVED A BOUNDARY. This one asks a different question:
 * can a real merchant actually START? Nine slices of isolation work are worth
 * nothing if the answer is no, and the pieces were built one at a time and
 * never walked end to end. Nothing here is seeded by a migration - Shop #1 got
 * its territories, its riders and its listings from data that already existed,
 * and Shop #2 gets none of that.
 *
 * SO THIS IS ONE JOURNEY, NOT A SUITE OF ENDPOINT TESTS, and the order is the
 * point. Each step depends on the last, the way it does for a real shopkeeper:
 *
 *      the platform registers the merchant and opens their shop
 *      the owner is put on its staff, which is what gives them a scope at all
 *   -- from here everything is the merchant's own doing --
 *      they set where they are and how far they deliver
 *      they draw their first territory
 *      they hire their first rider and give them that territory
 *      they list products off the shared catalogue at their own prices
 *      they open for business
 *      a customer finds them, buys, and the order reaches their own rider
 *      and the money shows up in their own earnings
 *
 * WHAT A FAILURE HERE MEANS. Not that a boundary leaked - that a shopkeeper
 * cannot open. Those are the bugs this slice exists to find, and they do not
 * show up in any test that starts from a fixture.
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
@DisplayName("A second kirana joins, and its first order goes out")
class SecondMerchantOnboardingTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ShopRepository shops;
    @Autowired private PlatformProperties platform;
    @Autowired private com.gpstore.territory.TerritoryResolver resolver;
    @Autowired private com.gpstore.service.OutboxWorker outbox;

    private final String tag = "onb" + System.nanoTime();

    private Long platformAdmin;
    private Long owner;
    private Long shopper;
    private Long merchantId;
    private Long shopId;
    private Long zoneId;
    private Long subzoneId;
    private Long riderId;
    private Long variantId;

    /** Where the new kirana is, and a square of ground around it. */
    private static final double SHOP_LAT = 26.4499;
    private static final double SHOP_LNG = 80.3319;
    private static final String BOUNDARY =
            "[[26.43,80.31],[26.43,80.35],[26.47,80.35],[26.47,80.31],[26.43,80.31]]";

    @BeforeEach
    void nobodyHasStartedYet() {
        TenantDefaults.install(PlatformMode.MULTI_SHOP_PRODUCTION,
                () -> shops.findByCode(platform.getFirstShopCode()).orElseThrow().getId());

        platformAdmin = newAccount("platform", Role.PLATFORM_ADMIN);
        owner = newAccount("owner", Role.ADMIN);
        shopper = newAccount("shopper", Role.CUSTOMER);

        // A product that already exists in the shared central catalogue (§10).
        // The new shop does not create products - it lists ones the platform
        // already defines, at its own price.
        variantId = jdbc.queryForObject(
                "SELECT id FROM product_variants ORDER BY id LIMIT 1", Long.class);

        resolver.invalidate();
    }

    @AfterEach
    void tidyUp() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
        resolver.invalidate();
        for (int attempt = 1; ; attempt++) {
            try {
                deleteFixture();
                break;
            } catch (org.springframework.dao.DataIntegrityViolationException stillReferenced) {
                if (attempt == 5) { throw stillReferenced; }
                try { Thread.sleep(200L * attempt); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw stillReferenced;
                }
            }
        }
        TenantDefaults.install(PlatformMode.SINGLE_SHOP,
                () -> shops.findByCode(platform.getFirstShopCode()).orElseThrow().getId());
    }

    private void deleteFixture() {
        if (shopId != null) {
            jdbc.update("DELETE FROM outbox_events WHERE shop_id = ?", shopId);
            jdbc.update("DELETE FROM order_scan_events WHERE shop_id = ?", shopId);
            jdbc.update("DELETE FROM deliveries WHERE shop_id = ?", shopId);
            jdbc.update("DELETE FROM delivery_batches WHERE shop_id = ?", shopId);
            jdbc.update("DELETE FROM payments WHERE shop_id = ?", shopId);
            // EVERYTHING THAT POINTS AT AN ORDER, or the shop row survives the
            // teardown and the next run's customer resolves into a leftover
            // kirana sitting at the same coordinates. Found exactly that way:
            // three abandoned shops at 26.4499/80.3319 and an order that
            // landed in the oldest of them.
            jdbc.update("DELETE FROM notifications WHERE order_id IN "
                    + "(SELECT id FROM orders WHERE shop_id = ?)", shopId);
            jdbc.update("DELETE FROM invoices WHERE order_id IN "
                    + "(SELECT id FROM orders WHERE shop_id = ?)", shopId);
            jdbc.update("DELETE FROM order_returns WHERE order_id IN "
                    + "(SELECT id FROM orders WHERE shop_id = ?)", shopId);
            jdbc.update("DELETE FROM order_items WHERE order_id IN "
                    + "(SELECT id FROM orders WHERE shop_id = ?)", shopId);
            jdbc.update("DELETE FROM orders WHERE shop_id = ?", shopId);
            jdbc.update("DELETE FROM order_groups WHERE customer_id = ?", shopper);
            jdbc.update("DELETE FROM cart_items WHERE shop_id = ?", shopId);
            jdbc.update("DELETE FROM subzone_backup_partners WHERE shop_id = ?", shopId);
            jdbc.update("UPDATE delivery_subzones SET primary_partner_id = NULL WHERE shop_id = ?", shopId);
            jdbc.update("DELETE FROM delivery_subzones WHERE shop_id = ?", shopId);
            jdbc.update("DELETE FROM delivery_zones WHERE shop_id = ?", shopId);
            jdbc.update("DELETE FROM delivery_partners WHERE shop_id = ?", shopId);
            jdbc.update("DELETE FROM inventory WHERE shop_id = ?", shopId);
            jdbc.update("DELETE FROM shop_product_variants WHERE shop_id = ?", shopId);
            jdbc.update("DELETE FROM shop_staff WHERE shop_id = ?", shopId);
            jdbc.update("DELETE FROM store_operations_settings WHERE shop_id = ?", shopId);
            jdbc.update("DELETE FROM delivery_pricing_settings WHERE shop_id = ?", shopId);
        }
        jdbc.update("DELETE FROM carts WHERE customer_id = ?", shopper);
        jdbc.update("DELETE FROM addresses WHERE customer_id = ?", shopper);
        if (shopId != null) {
            jdbc.update("DELETE FROM shops WHERE id = ?", shopId);
        }
        if (merchantId != null) {
            jdbc.update("DELETE FROM merchants WHERE id = ?", merchantId);
        }
        jdbc.update("DELETE FROM customers WHERE id in (?, ?, ?)", platformAdmin, owner, shopper);
    }

    @Test
    @DisplayName("the whole journey: registered, mapped, staffed, stocked, open, and delivering")
    void aSecondKiranaCanStartFromNothing() throws Exception {
        // ---- 1. the platform registers the merchant ----------------------
        String merchant = body(post("/api/platform/merchants"), platformAdmin, Role.PLATFORM_ADMIN,
                """
                {"legalName":"Second Kirana %s","displayName":"Second Kirana",
                 "contactPhone":"9800000001","ownerCustomerId":%d,"demo":true}
                """.formatted(tag, owner));
        merchantId = idIn(merchant);
        assertNotNull(merchantId, "the merchant was not registered: " + merchant);

        // A NEW MERCHANT IS AN APPLICATION, NOT A SHOP. It goes to review and
        // is approved from there; a shop cannot be opened under one that has
        // not been. Two deliberate steps rather than one, because "somebody
        // looked at this business's papers" is a real event and skipping
        // straight to APPROVED would make it unrecorded.
        perform(put("/api/platform/merchants/" + merchantId + "/status"),
                platformAdmin, Role.PLATFORM_ADMIN,
                "{\"status\":\"PENDING_REVIEW\",\"reason\":\"papers submitted\"}", 200);
        perform(put("/api/platform/merchants/" + merchantId + "/status"),
                platformAdmin, Role.PLATFORM_ADMIN,
                "{\"status\":\"APPROVED\",\"reason\":\"papers checked\"}", 200);

        // ---- 2. the platform opens their shop ----------------------------
        String shop = body(post("/api/platform/shops"), platformAdmin, Role.PLATFORM_ADMIN,
                """
                {"merchantId":%d,"code":"ONB-%s","displayName":"Second Kirana",
                 "latitude":%s,"longitude":%s,"maxDeliveryRadiusKm":5,"timeZone":"Asia/Kolkata"}
                """.formatted(merchantId, tag, SHOP_LAT, SHOP_LNG));
        shopId = idIn(shop);
        assertNotNull(shopId, "the shop was not opened: " + shop);

        // ---- 3. the owner joins its staff, which is what gives them a scope
        perform(post("/api/platform/shops/" + shopId + "/staff"),
                platformAdmin, Role.PLATFORM_ADMIN,
                "{\"customerId\":%d,\"asDefault\":true}".formatted(owner), 200);

        // ================= from here it is the merchant's own doing =========

        // ---- 4. they can see their own shop, and only their own -----------
        String profile = body(get("/api/shop/profile"), owner, Role.ADMIN, null);
        assertTrue(profile.contains("\"id\":" + shopId),
                "the owner could not read their own shop: " + profile);

        // ---- 4b. and are told exactly what is still missing ----------------
        //
        // THE SCREEN THAT DID NOT EXIST. Everything below this point is a step
        // a shopkeeper has to know to take, and before this slice the only
        // thing anyone was told when a step was missing was "This shop is not
        // currently taking orders" - the right sentence for a customer and
        // useless to the person who can fix it.
        String beforeAnything = body(get("/api/shop/readiness"), owner, Role.ADMIN, null);
        assertTrue(beforeAnything.contains("\"canTakeOrders\":false"),
                "a shop with no stock, no listings and an un-activated merchant is not ready: "
                        + beforeAnything);
        assertTrue(beforeAnything.contains("\"name\":\"merchant-active\",\"done\":false"),
                "the checklist must name the merchant switch - it is the one that catches a real "
                        + "onboarding out, because the shop looks fine: " + beforeAnything);
        assertTrue(beforeAnything.contains("\"name\":\"has-listings\",\"done\":false"),
                "a shop with an empty shelf must be told so: " + beforeAnything);

        // ---- 5. they draw their first territory ---------------------------
        String zone = body(post("/api/admin/territory/zones"), owner, Role.ADMIN,
                "{\"code\":\"K1\",\"name\":\"Around the shop\",\"active\":true}");
        zoneId = idIn(zone);
        assertNotNull(zoneId, "the shop could not draw a zone: " + zone);

        String subzone = body(post("/api/admin/territory/zones/" + zoneId + "/subzones"),
                owner, Role.ADMIN,
                """
                {"code":"K1A","name":"The lanes behind the market","active":true,
                 "maxConcurrentOrders":12,"boundary":"%s"}
                """.formatted(BOUNDARY.replace("\"", "\\\"")));
        subzoneId = idIn(subzone);
        assertNotNull(subzoneId, "the shop could not draw a territory: " + subzone);

        // ---- 6. they hire their first rider -------------------------------
        String rider = body(post("/api/delivery-partners"), owner, Role.ADMIN,
                """
                {"name":"First Rider %s","mobile":"88%s","vehicleType":"BIKE",
                 "available":true,"active":true}
                """.formatted(tag, String.valueOf(10000000 + (int) (Math.random() * 89999999))));
        riderId = idIn(rider);
        assertNotNull(riderId, "the shop could not hire a rider: " + rider);

        // ---- 7. and give that rider the territory -------------------------
        perform(put("/api/admin/territory/subzones/" + subzoneId + "/primary-partner"),
                owner, Role.ADMIN, "{\"partnerId\":%d}".formatted(riderId), 200);

        // ---- 8. they list a product off the shared catalogue --------------
        perform(put("/api/shop/listings/" + variantId), owner, Role.ADMIN,
                """
                {"sellingPrice":95.00,"costPrice":70.00,"mrp":110.00,
                 "available":true,"active":true}
                """, 200);

        // ---- 8b. and put stock behind it, THROUGH THE API ------------------
        //
        // THIS STEP USED TO BE A jdbc INSERT INTO inventory, and that was the
        // most useful thing this test ever said. There was no route a
        // shopkeeper could call to stock a listing they had just created:
        // /api/inventory wants a whole Inventory entity and the stock row's own
        // id, which a new listing has not got. The test reached around the API
        // into the table, the onboarding looked complete, and a real merchant
        // would have been stuck at exactly this point.
        //
        // If this call is ever replaced by SQL again, the flow has stopped
        // being completable through the API and the test has stopped telling
        // the truth about onboarding.
        perform(put("/api/shop/listings/" + variantId + "/stock"), owner, Role.ADMIN,
                "{\"stock\":50,\"minimumStock\":5}", 200);

        // ---- 9. they open for business ------------------------------------
        //
        // TWO SWITCHES, NOT ONE, and both are real. APPROVED means "the papers
        // are in order"; ACTIVE means "this business is trading". A shop can
        // be ACTIVE under a merchant that is only APPROVED - everything is
        // built, nothing sells - which is the state a real onboarding sits in
        // right up until somebody throws the second switch.
        perform(put("/api/platform/merchants/" + merchantId + "/status"),
                platformAdmin, Role.PLATFORM_ADMIN,
                "{\"status\":\"ACTIVE\",\"reason\":\"open for business\"}", 200);
        perform(put("/api/platform/shops/" + shopId + "/status"),
                platformAdmin, Role.PLATFORM_ADMIN,
                "{\"status\":\"ACTIVE\",\"reason\":\"ready to trade\"}", 200);

        // ---- 9b. and the checklist now says they are ready -----------------
        String afterEverything = body(get("/api/shop/readiness"), owner, Role.ADMIN, null);
        assertTrue(afterEverything.contains("\"canTakeOrders\":true"),
                "every step has been taken and the shop still reports itself unready: "
                        + afterEverything);
        assertFalse(afterEverything.contains("\"blocking\":true,\"detail\"")
                        && afterEverything.contains("\"done\":false,\"blocking\":true"),
                "no blocking step may still be outstanding: " + afterEverything);

        // ---- 10. a customer standing nearby can find them ------------------
        String nearby = body(get("/api/marketplace/shops?lat=" + SHOP_LAT + "&lng=" + SHOP_LNG),
                shopper, Role.CUSTOMER, null);
        assertTrue(nearby.contains("\"shopId\":" + shopId),
                "a customer at the shop's own front door could not find it: " + nearby);

        // ---- 11. and their catalogue shows the shop's own price ------------
        String listings = body(get("/api/shop/listings"), owner, Role.ADMIN, null);
        assertTrue(listings.contains("95.0"),
                "the shop's own price is not on its own shelf: " + listings);

        // ---- 12. the shop's earnings start at zero, not at somebody else's -
        String earningsBefore = body(get("/api/shop/earnings?days=2"), owner, Role.ADMIN, null);
        assertTrue(earningsBefore.contains("\"grossSales\":0"),
                "a brand-new shop's first statement must read zero, not the marketplace's "
                        + "figures: " + earningsBefore);

        // ---- 13. the customer puts an address inside the shop's territory --
        jdbc.update("""
                INSERT INTO addresses (customer_id, house_no, street, city, state, pincode,
                                       latitude, longitude, default_address)
                VALUES (?, '4', 'Market Road', 'Kanpur', 'UP', '208001', ?, ?, true)
                """, shopper, SHOP_LAT + 0.005, SHOP_LNG + 0.005);
        Long addressId = jdbc.queryForObject(
                "SELECT id FROM addresses WHERE customer_id = ?", Long.class, shopper);

        // ---- 14. and buys something ---------------------------------------
        // The CUSTOMER's own basket route. /api/cart-items is the admin one -
        // a shopper cannot reach it, which is the fix recorded in
        // SecurityConfig about reading not being writing.
        MvcResult added = send(post("/api/carts/add?variantId=" + variantId + "&quantity=2"),
                shopper, Role.CUSTOMER, null);
        assertEquals(200, added.getResponse().getStatus(),
                "the customer could not buy from the shop they just found. Listings this shop ("
                        + shopId + ") has: "
                        + jdbc.queryForList("SELECT product_variant_id, selling_price, available, "
                                + "active FROM shop_product_variants WHERE shop_id = ?", shopId)
                        + "; stock rows: "
                        + jdbc.queryForList("SELECT product_variant_id, stock FROM inventory "
                                + "WHERE shop_id = ?", shopId)
                        + "; shops serving the customer: "
                        + jdbc.queryForList("SELECT id, status FROM shops WHERE active AND "
                                + "latitude IS NOT NULL")
                        + " -> " + added.getResponse().getContentAsString());

        String placed = body(post("/api/orders/place")
                        .header("Idempotency-Key", java.util.UUID.randomUUID().toString()),
                shopper, Role.CUSTOMER,
                """
                {"addressId":%d,"paymentMethod":"COD"}
                """.formatted(addressId));
        assertTrue(placed.contains("orderId") || placed.contains("orderNumber"),
                "the first order was not placed: " + placed);

        // ---- 15. THE ORDER IS THE SHOP'S, and so is the money -------------
        Long ordersForShop = jdbc.queryForObject(
                "SELECT count(*) FROM orders WHERE shop_id = ?", Long.class, shopId);
        assertEquals(1L, ordersForShop,
                "the new shop's first order did not land in the new shop (" + shopId + "). "
                        + "This customer's orders are in shops "
                        + jdbc.queryForList("SELECT shop_id FROM orders WHERE customer_id = ?",
                                Long.class, shopper)
                        + " and their cart lines were stamped "
                        + jdbc.queryForList("SELECT shop_id FROM cart_items WHERE cart_id IN "
                                + "(SELECT id FROM carts WHERE customer_id = ?)", Long.class, shopper)
                        + ". Response was: " + placed);

        java.math.BigDecimal total = jdbc.queryForObject(
                "SELECT total_amount FROM orders WHERE shop_id = ?", java.math.BigDecimal.class, shopId);
        assertNotNull(total);
        assertTrue(total.compareTo(new java.math.BigDecimal("190.00")) >= 0,
                "two units at the shop's OWN price of 95 is at least 190 before delivery; got "
                        + total + ". A different number means the order was priced off somebody "
                        + "else's shelf");

        // A COD ORDER IS CONFIRMED THE MOMENT IT IS PLACED, deliberately -
        // there is no payment-confirmation step to wait for, and leaving it in
        // PENDING_CONFIRMATION would mean every cash order in the shop sat
        // unassigned until somebody clicked. An ONLINE order does wait, and
        // the gateway callback is what advances it.
        String status = jdbc.queryForObject(
                "SELECT order_status FROM orders WHERE shop_id = ?", String.class, shopId);
        assertEquals("CONFIRMED", status,
                "a cash order must be ready to dispatch as soon as it is placed");

        // ---- 16. and it went to the shop's own rider, in its own territory -
        //
        // ASSIGNMENT IS DURABLE OUTBOX WORK, not a fire-and-forget callback.
        // Losing a rider assignment because the process was redeployed
        // mid-flight is a real order nobody delivers, so it is written to the
        // outbox inside the checkout transaction and drained by a worker.
        // Tests pin that worker's interval to an hour to keep background
        // noise out, so the journey drains it by hand - which is also the
        // honest thing to do: it proves the assignment came through the
        // durable path a restart would survive, not through a callback that
        // happened to still be running.
        outbox.drain();
        awaitDeliveryFor(shopId);

        // The rider is on the BATCH - a delivery joins the open batch that
        // rider already has for this territory, which is how several orders
        // for one neighbourhood go out on one trip.
        Long riderOnTheOrder = jdbc.queryForObject(
                "SELECT b.delivery_partner_id FROM deliveries d "
                        + "JOIN delivery_batches b ON b.id = d.batch_id WHERE d.shop_id = ?",
                Long.class, shopId);
        assertEquals(riderId, riderOnTheOrder,
                "the order did not reach the shop's own rider. W4: a worker belongs to one shop, "
                        + "and the whole chain - territory, rider, order - has to land in the same "
                        + "one");

        Long territoryOnTheOrder = jdbc.queryForObject(
                "SELECT subzone_id FROM deliveries WHERE shop_id = ?", Long.class, shopId);
        assertEquals(subzoneId, territoryOnTheOrder,
                "the delivery was not recorded in the territory the shop drew for it");

        // ---- 17. the takings show up on the shop's own statement ----------
        String earningsAfter = body(get("/api/shop/earnings?days=2"), owner, Role.ADMIN, null);
        assertFalse(earningsAfter.contains("\"grossSales\":0,"),
                "the first sale never reached the shop's own earnings: " + earningsAfter);
        assertTrue(earningsAfter.contains("\"orderCount\":1"),
                "the statement should show exactly the one order this shop has taken: "
                        + earningsAfter);
    }

    /** Waits for the post-commit assignment, or fails saying it never happened. */
    private void awaitDeliveryFor(Long shop) throws InterruptedException {
        for (int attempt = 0; attempt < 100; attempt++) {
            Long deliveries = jdbc.queryForObject(
                    "SELECT count(*) FROM deliveries WHERE shop_id = ?", Long.class, shop);
            if (deliveries != null && deliveries > 0) {
                return;
            }
            Thread.sleep(100);
        }
        fail("the first order was placed but never assigned to anybody. autoAssignBestEffort "
                + "swallows every exception by design - so a shop whose dispatch cannot work "
                + "takes orders that silently never reach a rider, and nothing on any screen "
                + "says so");
    }

    // ------------------------------------------------------------- plumbing

    private String body(MockHttpServletRequestBuilder request, Long accountId, Role role,
                        String json) throws Exception {
        MvcResult result = send(request, accountId, role, json);
        int status = result.getResponse().getStatus();
        String content = result.getResponse().getContentAsString();
        assertTrue(status >= 200 && status < 300,
                request.toString() + " returned " + status + ": " + content);
        return content;
    }

    private void perform(MockHttpServletRequestBuilder request, Long accountId, Role role,
                         String json, int expected) throws Exception {
        MvcResult result = send(request, accountId, role, json);
        assertEquals(expected, result.getResponse().getStatus(),
                "unexpected status: " + result.getResponse().getContentAsString());
    }

    private MvcResult send(MockHttpServletRequestBuilder request, Long accountId, Role role,
                           String json) throws Exception {
        request.with(authentication(tokenFor(accountId, role)));
        if (json != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        return mockMvc.perform(request).andReturn();
    }

    private static Long idIn(String json) {
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("\"id\"\\s*:\\s*(\\d+)").matcher(json);
        return matcher.find() ? Long.valueOf(matcher.group(1)) : null;
    }

    private Long newAccount(String kind, Role role) {
        String email = tag + "-" + kind + "@example.test";
        jdbc.update("""
                INSERT INTO customers (full_name, email, mobile_number, password, role, active)
                VALUES (?, ?, ?, 'not-a-real-hash', ?, true)
                """, kind + " " + tag, email,
                "9" + (100000000 + (int) (Math.random() * 899999999)), role.name());
        return jdbc.queryForObject("SELECT id FROM customers WHERE email = ?", Long.class, email);
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
