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

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Two merchants trading, and who is allowed to see both sets of books.
 *
 * THE ONE PLACE FIGURES FROM DIFFERENT MERCHANTS SIT SIDE BY SIDE. Every other
 * surface in this system is built so that cannot happen; the marketplace
 * overview is built so it can, for exactly one credential, and that makes it
 * the route worth attacking. So this is written from both ends: a platform
 * administrator sees the market, and a shop owner - senior inside their own
 * shop, holding every permission a shop can grant - sees their own line and is
 * refused the route entirely.
 *
 * WHY A SEPARATE CLASS. It needs MULTI_SHOP_PRODUCTION, where the resolver
 * stops answering "Shop #1" to everybody and identity actually decides. Under
 * SINGLE_SHOP the same endpoint correctly reports one shop, which
 * ShopReportingIsolationTest asserts - and that is §2 working, not a gap.
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
@DisplayName("Looking at the whole market, and who may")
class MarketplaceOversightTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ShopRepository shops;
    @Autowired private MerchantRepository merchants;
    @Autowired private PlatformProperties platform;
    @Autowired private ShopMembership membership;

    private final String tag = "mko" + System.nanoTime();

    private long shopA;
    private long shopB;
    private Long merchantB;
    private Long ownerB;
    private Long platformAdmin;
    private long orderAId;
    private long orderBId;

    private static final BigDecimal SALE_A = new BigDecimal("1100.00");
    private static final BigDecimal SALE_B = new BigDecimal("2200.00");

    /**
     * See MarketplaceIdentityTest for why a class that changes the mode must
     * put it back: TenantDefaults is a static holder shared by every cached
     * Spring context in the run, and a stale MULTI_SHOP would make later
     * SINGLE_SHOP tests fail in an order-dependent way.
     */
    private void installDefaultsFor(PlatformMode mode) {
        Long shopOne = shops.findByCode(platform.getFirstShopCode()).orElseThrow().getId();
        TenantDefaults.install(mode, () -> shopOne);
    }

    @BeforeEach
    void twoMerchantsBothTrading() {
        installDefaultsFor(PlatformMode.MULTI_SHOP_PRODUCTION);
        shopA = shops.findByCode(platform.getFirstShopCode()).orElseThrow().getId();

        Merchant second = new Merchant();
        second.setLegalName("Oversight merchant " + tag);
        second.setDisplayName("Oversight B");
        second.setStatus(MerchantStatus.ACTIVE);
        second.setIsDemo(Boolean.TRUE);
        second.setActive(Boolean.TRUE);
        merchantB = merchants.save(second).getId();

        Shop b = new Shop();
        b.setMerchantId(merchantB);
        b.setCode("MKO-" + tag);
        b.setDisplayName("Oversight shop B");
        b.setStatus(ShopStatus.ACTIVE);
        b.setIsDemo(Boolean.TRUE);
        b.setActive(Boolean.TRUE);
        shopB = shops.save(b).getId();

        ownerB = newAccount("ownerB", Role.ADMIN);
        platformAdmin = newAccount("platform", Role.PLATFORM_ADMIN);
        membership.grant(shopB, ownerB, true);

        orderAId = insertOrder("MKOA-" + tag, shopA, SALE_A);
        orderBId = insertOrder("MKOB-" + tag, shopB, SALE_B);
    }

    @AfterEach
    void tidyUp() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
        jdbc.update("DELETE FROM orders WHERE id in (?, ?)", orderAId, orderBId);
        jdbc.update("DELETE FROM shop_staff WHERE shop_id = ?", shopB);
        jdbc.update("DELETE FROM store_operations_settings WHERE shop_id = ?", shopB);
        jdbc.update("DELETE FROM delivery_pricing_settings WHERE shop_id = ?", shopB);
        jdbc.update("DELETE FROM customers WHERE id in (?, ?)", ownerB, platformAdmin);
        jdbc.update("DELETE FROM shops WHERE id = ?", shopB);
        jdbc.update("DELETE FROM merchants WHERE id = ?", merchantB);
        installDefaultsFor(PlatformMode.SINGLE_SHOP);
    }

    @Test
    @DisplayName("a platform admin sees a line for every shop that traded")
    void theOperatorSeesTheWholeMarket() throws Exception {
        String body = asPlatformAdmin();

        assertTrue(body.contains("\"shopId\":" + shopA),
                "Shop A is missing from the marketplace overview: " + body);
        assertTrue(body.contains("\"shopId\":" + shopB),
                "Shop B is missing from the marketplace overview: " + body);
        assertTrue(body.contains("2200.00"),
                "Shop B's takings are missing, so the roll-up is not reading its orders");
    }

    @Test
    @DisplayName("each shop's line carries that shop's own money, not a shared total")
    void theLinesAreGroupedNotPooled() throws Exception {
        com.fasterxml.jackson.databind.JsonNode overview =
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(asPlatformAdmin());

        java.util.Map<Long, BigDecimal> grossByShop = new java.util.HashMap<>();
        for (com.fasterxml.jackson.databind.JsonNode line : overview.get("shops")) {
            Long shop = line.get("shopId").asLong();
            assertFalse(grossByShop.containsKey(shop),
                    "shop " + shop + " appears twice - the roll-up is not grouping by shop");
            grossByShop.put(shop, line.get("grossSales").decimalValue());
        }

        // Shop #1 carries the whole existing business, so its figure is
        // whatever it is; what matters is that Shop B's line is Shop B's sale
        // exactly, and that the two shops are not reading the same number.
        assertEquals(0, SALE_B.compareTo(grossByShop.get(shopB)),
                "Shop B's line is not Shop B's own takings");
        assertTrue(grossByShop.get(shopA).compareTo(SALE_B) != 0,
                "both shops report the same figure, which means the query is pooling the market "
                        + "and labelling each row with a different shop id");

        // The totals row is the sum of the lines, which is what makes it a
        // marketplace figure rather than one shop's repeated.
        BigDecimal summed = grossByShop.values().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, summed.compareTo(overview.get("totals").get("grossSales").decimalValue()),
                "the marketplace total does not add up from the per-shop lines");
    }

    @Test
    @DisplayName("a shop owner is refused the marketplace overview outright")
    void aShopOwnerCannotOpenTheMarket() throws Exception {
        int status = mockMvc.perform(get("/api/platform/overview?days=2")
                        .with(authentication(tokenFor(ownerB, Role.ADMIN))))
                .andReturn().getResponse().getStatus();

        assertEquals(403, status,
                "a shop owner reached the marketplace overview. ADMIN is every permission a SHOP "
                        + "can grant, and PLATFORM_ADMIN is deliberately not one of them - "
                        + "RolePermissions subtracts it - so seniority inside one kirana never "
                        + "becomes sight of another's books");
    }

    @Test
    @DisplayName("a shop owner's own earnings are their own shop's, in the same marketplace")
    void aShopOwnerSeesOnlyTheirOwnTill() throws Exception {
        String body = mockMvc.perform(get("/api/shop/earnings?days=2")
                        .with(authentication(tokenFor(ownerB, Role.ADMIN))))
                .andReturn().getResponse().getContentAsString();

        assertTrue(body.contains("2200.00"),
                "Shop B's owner cannot see Shop B's own takings - isolation that hides your own "
                        + "money is an outage: " + body);
        assertFalse(body.contains("1100.00"),
                "Shop B's owner is being shown Shop A's takings: " + body);
        assertFalse(body.contains("3300.00"),
                "Shop B's statement carries the marketplace's total rather than its own");
    }

    @Test
    @DisplayName("naming another shop in the header does not move a shopkeeper into it")
    void aHeaderCannotMoveAShopkeeperIntoAnotherShop() throws Exception {
        // §78: a client shop id may only NARROW an already-authorized scope.
        // Shop B's owner is not on Shop A's staff, so there is nothing to
        // narrow to and the request must be refused rather than answered with
        // Shop A's money.
        int status = mockMvc.perform(get("/api/shop/earnings?days=2")
                        .header("X-Shop-Id", String.valueOf(shopA))
                        .with(authentication(tokenFor(ownerB, Role.ADMIN))))
                .andReturn().getResponse().getStatus();

        assertNotEquals(200, status,
                "changing a header handed one merchant another merchant's earnings");
    }

    private String asPlatformAdmin() throws Exception {
        return mockMvc.perform(get("/api/platform/overview?days=2")
                        .with(authentication(tokenFor(platformAdmin, Role.PLATFORM_ADMIN))))
                .andReturn().getResponse().getContentAsString();
    }

    private long insertOrder(String number, long shopId, BigDecimal amount) {
        jdbc.update("""
                INSERT INTO orders (customer_id, order_number, total_amount, order_status,
                                    payment_status, order_date, shop_id)
                VALUES (?, ?, ?, 'DELIVERED', 'SUCCESS', now(), ?)
                """, ownerB, number, amount, shopId);
        return jdbc.queryForObject("SELECT id FROM orders WHERE order_number = ?", Long.class, number);
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

    /** Exactly the principal and authorities JwtFilter builds for a real token. */
    private UsernamePasswordAuthenticationToken tokenFor(Long customerId, Role role) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        for (String authority : RolePermissions.authorityNames(role)) {
            authorities.add(new SimpleGrantedAuthority(authority));
        }
        return new UsernamePasswordAuthenticationToken(
                new AuthenticatedUser(customerId, tag + "@example.test", role.name()),
                null, authorities);
    }
}
