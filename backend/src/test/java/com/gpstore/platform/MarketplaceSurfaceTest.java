package com.gpstore.platform;

import com.gpstore.entity.Role;
import com.gpstore.ordergroup.OrderGroup;
import com.gpstore.ordergroup.OrderGroupRepository;
import com.gpstore.ordergroup.OrderGroupService;
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

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * The surface a customer's app actually talks to, and who may reach it.
 *
 * THE FIRST SCREEN PROBLEM. Every other endpoint runs inside a shop scope
 * resolved from the credential, and a customer who has just installed the app
 * has no shop. Asking them to have one before they may ask which shops exist
 * is a 403 on the first screen - so shop discovery runs platform-wide, returns
 * only what a customer may see anyway, and answers an empty list rather than
 * an error when nobody delivers to them.
 *
 * AND THE CHECKOUT THEY THINK THEY PLACED. A basket split across two kiranas
 * is two orders; the group is what the customer opened one screen to see, and
 * cancelling it has to answer per shop because the outcome genuinely is per
 * shop.
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
@DisplayName("Finding a shop, and seeing the checkout you actually placed")
class MarketplaceSurfaceTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ShopRepository shops;
    @Autowired private MerchantRepository merchants;
    @Autowired private PlatformProperties platform;
    @Autowired private ShopDiscovery discovery;
    @Autowired private OrderGroupRepository groups;
    @Autowired private OrderGroupService groupService;

    private final String tag = "mkts" + System.nanoTime();

    private long shopOne;
    private long nearbyShop;
    private Long merchantId;
    private Long customerId;
    private Long otherCustomerId;
    private Long groupId;

    private static final double LAT = 12.9111;
    private static final double LNG = 77.6111;

    @BeforeEach
    void aMarketplaceWithOneNearbyShop() {
        shopOne = shops.findByCode(platform.getFirstShopCode()).orElseThrow().getId();

        Merchant m = new Merchant();
        m.setLegalName("Surface fixture " + tag);
        m.setDisplayName("Nearby kirana");
        m.setStatus(MerchantStatus.ACTIVE);
        m.setIsDemo(Boolean.TRUE);
        m.setActive(Boolean.TRUE);
        merchantId = merchants.save(m).getId();

        Shop nearby = new Shop();
        nearby.setMerchantId(merchantId);
        nearby.setCode("MKTS-" + tag);
        nearby.setDisplayName("Nearby kirana");
        nearby.setStatus(ShopStatus.ACTIVE);
        nearby.setLatitude(LAT + 0.0045);
        nearby.setLongitude(LNG);
        nearby.setMaxDeliveryRadiusKm(new BigDecimal("3"));
        nearby.setTimeZone("Asia/Kolkata");
        nearby.setSupportPhone("9000000000");
        nearby.setIsDemo(Boolean.TRUE);
        nearby.setActive(Boolean.TRUE);
        nearbyShop = shops.save(nearby).getId();

        customerId = newCustomer("shopper");
        otherCustomerId = newCustomer("stranger");

        // A checkout of this customer's, with one shop order under it.
        OrderGroup group = new OrderGroup();
        group.setGroupNumber("GRP-" + tag);
        group.setCustomerId(customerId);
        group.setTotalAmount(new BigDecimal("250.00"));
        group.setShopCount(1);
        groupId = groups.save(group).getId();

        jdbc.update("""
                INSERT INTO orders (customer_id, order_number, total_amount, order_status,
                                    payment_status, order_date, shop_id, order_group_id)
                VALUES (?, ?, 250.00, 'CONFIRMED', 'COD_PENDING', now(), ?, ?)
                """, customerId, "SURF-" + tag, shopOne, groupId);
    }

    /**
     * Retried, because cancelling an order queues a notification through
     * AfterCommitExecutor - it lands on another thread once the transaction
     * commits, and the delete usually wins that race and sometimes does not.
     */
    @AfterEach
    void tidyUp() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
        for (int attempt = 1; ; attempt++) {
            try {
                deleteFixture();
                return;
            } catch (org.springframework.dao.DataIntegrityViolationException stillReferenced) {
                if (attempt == 5) {
                    throw stillReferenced;
                }
                try {
                    Thread.sleep(200L * attempt);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw stillReferenced;
                }
            }
        }
    }

    private void deleteFixture() {
        jdbc.update("DELETE FROM notifications WHERE order_id IN (SELECT id FROM orders WHERE customer_id in (?, ?))",
                customerId, otherCustomerId);
        jdbc.update("DELETE FROM orders WHERE customer_id in (?, ?)", customerId, otherCustomerId);
        jdbc.update("DELETE FROM order_groups WHERE customer_id in (?, ?)", customerId, otherCustomerId);
        jdbc.update("DELETE FROM customers WHERE id in (?, ?)", customerId, otherCustomerId);
        jdbc.update("DELETE FROM store_operations_settings WHERE shop_id = ?", nearbyShop);
        jdbc.update("DELETE FROM delivery_pricing_settings WHERE shop_id = ?", nearbyShop);
        jdbc.update("DELETE FROM shops WHERE id = ?", nearbyShop);
        jdbc.update("DELETE FROM merchants WHERE id = ?", merchantId);
    }

    // ------------------------------------------------ discovery, before a shop

    @Test
    @DisplayName("an app with no credential can ask which shops deliver to a point")
    void discoveryNeedsNoShopAndNoSignIn() throws Exception {
        MvcResult result = mockMvc.perform(
                get("/api/marketplace/shops").param("lat", String.valueOf(LAT))
                        .param("lng", String.valueOf(LNG))).andReturn();

        assertEquals(200, result.getResponse().getStatus(),
                "the first screen of a freshly installed app cannot require a shop it does not "
                        + "have yet; body: " + result.getResponse().getContentAsString());
        assertTrue(result.getResponse().getContentAsString().contains("MKTS-" + tag),
                "the nearby shop must be offered");
    }

    @Test
    @DisplayName("nowhere in range answers an empty list, not an error")
    void nobodyInRangeIsAnAnswerNotAFailure() throws Exception {
        // The Bay of Bengal. No kirana delivers here.
        MvcResult result = mockMvc.perform(get("/api/marketplace/shops")
                .param("lat", "15.0").param("lng", "88.0")).andReturn();

        assertEquals(200, result.getResponse().getStatus(),
                "'no shop delivers to you yet' is a screen the app can draw; a 403 is not, and "
                        + "would be telling a customer they may not live where they live");
        assertEquals("[]", result.getResponse().getContentAsString().trim());
    }

    @Test
    @DisplayName("no coordinates matches no shop rather than every shop")
    void noPinFailsClosed() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/marketplace/shops")).andReturn();
        assertEquals(200, result.getResponse().getStatus());
        assertEquals("[]", result.getResponse().getContentAsString().trim());
    }

    @Test
    @DisplayName("a storefront a customer may not see answers 404, not 403")
    void aSuspendedStorefrontIsSimplyAbsent() throws Exception {
        Shop shop = shops.findById(nearbyShop).orElseThrow();
        shop.setStatus(ShopStatus.SUSPENDED);
        shops.save(shop);

        assertEquals(404, mockMvc.perform(get("/api/marketplace/shops/" + nearbyShop))
                .andReturn().getResponse().getStatus(),
                "whether a particular shop is suspended is between the platform and that "
                        + "merchant - a 403 would tell every customer that it exists and is in "
                        + "trouble");
        assertFalse(discovery.isBrowsableByCustomers(nearbyShop));
    }

    @Test
    @DisplayName("discovery exposes a storefront, never a merchant's own business")
    void discoveryShowsAShopWindowAndNothingBehindIt() throws Exception {
        String body = mockMvc.perform(get("/api/marketplace/shops/" + nearbyShop))
                .andReturn().getResponse().getContentAsString();

        assertTrue(body.contains("Nearby kirana"));
        assertFalse(body.contains("Surface fixture " + tag),
                "the merchant's legal name is the platform's business and the shopkeeper's, not "
                        + "a browsing customer's");
        assertFalse(body.toLowerCase(java.util.Locale.ROOT).contains("costprice"),
                "and nothing commercial belongs on a shop window");
    }

    // ------------------------------------------------ the group, and cancelling it

    @Test
    @DisplayName("a customer sees the checkout they placed, with each shop's part")
    void aCustomerSeesTheirOwnCheckout() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/orders/groups")
                .with(authentication(tokenFor(customerId, Role.CUSTOMER)))).andReturn();

        assertEquals(200, result.getResponse().getStatus());
        assertTrue(result.getResponse().getContentAsString().contains("GRP-" + tag));
        assertTrue(result.getResponse().getContentAsString().contains("SURF-" + tag),
                "the shop orders under it are the point of the screen");
    }

    @Test
    @DisplayName("another customer's checkout is not found, whatever id they guess")
    void aCheckoutIsNotReadableByAnotherCustomer() throws Exception {
        assertEquals(404, mockMvc.perform(get("/api/orders/groups/" + groupId)
                        .with(authentication(tokenFor(otherCustomerId, Role.CUSTOMER))))
                .andReturn().getResponse().getStatus(),
                "a group has no shop, so the tenant filter has nothing to say about it - what "
                        + "keeps customers apart here is the ownership check, and 404 rather than "
                        + "403 keeps a guessed id from confirming somebody else's checkout exists");

        assertEquals(200, mockMvc.perform(get("/api/orders/groups/" + groupId)
                        .with(authentication(tokenFor(customerId, Role.CUSTOMER))))
                .andReturn().getResponse().getStatus(),
                "and the customer who placed it must still be able to open it");
    }

    @Test
    @DisplayName("another customer cannot cancel a checkout that is not theirs")
    void aCheckoutIsNotCancellableByAnotherCustomer() throws Exception {
        assertEquals(404, mockMvc.perform(put("/api/orders/groups/" + groupId + "/cancel")
                        .with(authentication(tokenFor(otherCustomerId, Role.CUSTOMER))))
                .andReturn().getResponse().getStatus());

        assertEquals("CONFIRMED", jdbc.queryForObject(
                "SELECT order_status FROM orders WHERE order_number = ?", String.class, "SURF-" + tag),
                "the order actually changed state at a stranger's request");
    }

    @Test
    @DisplayName("cancelling a checkout answers per shop, because the outcome is per shop")
    void cancellingAnswersPerShop() {
        OrderGroupService.CancelResult result = TenantContext.runWithin(TenantScope.platform(),
                () -> groupService.cancelWholeCheckout(customerId, groupId));

        assertEquals(1, result.outcomes().size(),
                "one shop in this checkout, so one outcome - a single success flag would hide "
                        + "which half of a two-shop basket was actually stopped");
        assertTrue(result.outcomes().get(0).cancelled());
        assertEquals(shopOne, result.outcomes().get(0).shopId());
        assertTrue(result.allCancelled());

        assertEquals("CANCELLED", jdbc.queryForObject(
                "SELECT order_status FROM orders WHERE order_number = ?", String.class, "SURF-" + tag));
    }

    @Test
    @DisplayName("a shop order that cannot be cancelled is reported, not thrown")
    void aRefusedCancellationIsAnOutcomeNotAnError() {
        jdbc.update("UPDATE orders SET order_status = 'OUT_FOR_DELIVERY' WHERE order_number = ?",
                "SURF-" + tag);

        OrderGroupService.CancelResult result = TenantContext.runWithin(TenantScope.platform(),
                () -> groupService.cancelWholeCheckout(customerId, groupId));

        assertFalse(result.allCancelled());
        assertFalse(result.outcomes().get(0).cancelled(),
                "an order already on its way cannot be cancelled - and giving up at the first "
                        + "refusal would leave a customer paying for the half they could have "
                        + "stopped, with no way to tell which half that was");
        assertNotNull(result.outcomes().get(0).reason(), "and they have to be told why");
    }

    // ------------------------------------------------------------ fixtures

    private Long newCustomer(String kind) {
        String email = tag + "-" + kind + "@example.test";
        jdbc.update("""
                INSERT INTO customers (full_name, email, mobile_number, password, role, active)
                VALUES (?, ?, ?, 'not-a-real-hash', 'CUSTOMER', true)
                """, kind + " " + tag, email,
                "9" + String.valueOf(System.nanoTime()).substring(0, 9));
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
