package com.gpstore.platform;

import com.gpstore.entity.Role;
import com.gpstore.security.AuthenticatedUser;
import com.gpstore.security.RolePermissions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * One login, several shops, and a wall between businesses.
 *
 * WHAT IS NOT HERE, ON PURPOSE. Per-shop pricing, delisting and the shared
 * catalogue row are already proved sixteen ways by CrossTenantShopCatalogTest -
 * a shop cannot reprice another's listing, cannot read its cost price, cannot
 * delist its items. Repeating that here would be a second copy of a guarantee,
 * and two copies of a guarantee drift.
 *
 * WHAT IS HERE is the part that had no test: a merchant with MORE THAN ONE
 * shop. Every existing fixture opens one. §87, §88 and §96 ask for three and
 * then four, and §50 asks for the wall between merchant A and merchant B to be
 * proved from the outside, over HTTP, with the header a real app sends.
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
@DisplayName("One merchant account runs many shops, and reaches no other merchant's")
class OneMerchantManyShopsTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private PlatformOnboardingService onboarding;
    @Autowired private ShopLifecycleService shopLifecycle;
    @Autowired private ShopMembership membership;
    @Autowired private ShopRepository shops;
    @Autowired private JdbcTemplate jdbc;

    private final List<Long> madeShops = new ArrayList<>();
    private final List<Long> madeMerchants = new ArrayList<>();
    private final List<Long> madeCustomers = new ArrayList<>();

    private static String uniquePhone() {
        return "9" + String.format("%09d", Math.abs(System.nanoTime() % 1_000_000_000L));
    }

    private PlatformOnboardingService.OnboardedMerchant onboardOne(String name) {
        String tag = "many" + System.nanoTime();
        var made = onboarding.onboard(name + " " + tag, "Owner " + tag,
                tag + "@example.test", uniquePhone(), null,
                26.7606, 83.3732, new BigDecimal("5.0"), null, false);
        madeMerchants.add(made.merchantId());
        madeShops.add(made.shopId());
        madeCustomers.add(made.ownerCustomerId());
        return made;
    }

    /** Another storefront under a merchant that already has one. */
    private Shop addShopTo(Long merchantId, String label) {
        Shop shop = shopLifecycle.open(merchantId, label + System.nanoTime(), label,
                26.7606, 83.3732, new BigDecimal("5.0"), "Asia/Kolkata");
        madeShops.add(shop.getId());
        return shop;
    }

    private UsernamePasswordAuthenticationToken owner(Long customerId) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        for (String authority : RolePermissions.authorityNames(Role.ADMIN)) {
            authorities.add(new SimpleGrantedAuthority(authority));
        }
        return new UsernamePasswordAuthenticationToken(
                new AuthenticatedUser(customerId, "owner@example.test", Role.ADMIN.name()),
                null, authorities);
    }

    @AfterEach
    void removeWhatThisMade() {
        for (Long shopId : madeShops) {
            jdbc.update("DELETE FROM shop_staff WHERE shop_id = ?", shopId);
            jdbc.update("DELETE FROM delivery_pricing_settings WHERE shop_id = ?", shopId);
            jdbc.update("DELETE FROM store_operations_settings WHERE shop_id = ?", shopId);
            jdbc.update("DELETE FROM audit_logs WHERE entity_id = ? AND entity_type = 'Shop'", shopId);
            jdbc.update("DELETE FROM shops WHERE id = ?", shopId);
        }
        for (Long merchantId : madeMerchants) {
            jdbc.update("DELETE FROM audit_logs WHERE entity_id = ? AND entity_type = 'Merchant'", merchantId);
            jdbc.update("DELETE FROM merchants WHERE id = ?", merchantId);
        }
        for (Long customerId : madeCustomers) {
            jdbc.update("DELETE FROM shop_staff WHERE customer_id = ?", customerId);
            jdbc.update("DELETE FROM refresh_tokens WHERE customer_id = ?", customerId);
            jdbc.update("DELETE FROM audit_logs WHERE entity_id = ? AND entity_type = 'Customer'", customerId);
            jdbc.update("DELETE FROM customers WHERE id = ?", customerId);
        }
        madeShops.clear();
        madeMerchants.clear();
        madeCustomers.clear();
    }

    // ------------------------------------------------ §87, §88, §96: 3, then 4

    @Test
    @DisplayName("four shops under one merchant, one account, four distinct ids")
    void fourShopsIsNotASpecialCase() throws Exception {
        var made = onboardOne("Deepak Enterprises");
        Shop hardware = addShopTo(made.merchantId(), "Deepak Hardware");
        Shop saree = addShopTo(made.merchantId(), "Deepak Saree");
        // THE FOURTH IS THE POINT (§88, §96). Three is the business example in
        // the specification; a fourth is what proves nothing in the schema or
        // the code stops at three.
        Shop electronics = addShopTo(made.merchantId(), "Deepak Electronics");

        Set<Long> ids = new HashSet<>(
                List.of(made.shopId(), hardware.getId(), saree.getId(), electronics.getId()));
        assertEquals(4, ids.size(), "four shops, four ids");

        for (Long shopId : ids) {
            assertEquals(made.merchantId(), shops.findById(shopId).orElseThrow().getMerchantId(),
                    "every shop must hang off the SAME merchant - a second merchant account per "
                            + "shop is exactly the shape §7 forbids");
        }

        // ONE MERCHANT ROW, not four. Opening shops must not quietly register
        // businesses.
        Integer merchantsForOwner = jdbc.queryForObject(
                "SELECT count(*) FROM merchants WHERE owner_customer_id = ?",
                Integer.class, made.ownerCustomerId());
        assertEquals(1, merchantsForOwner);

        // And one login reaches all four.
        assertEquals(4, membership.shopIdsFor(made.ownerCustomerId()).size(),
                "a merchant must not need a separate login per shop");
    }

    // -------------------------------------------------------- §89: switching

    @Test
    @DisplayName("the owner switches between all four, and each answers as itself")
    void switchingLandsWhereItSays() throws Exception {
        var made = onboardOne("Switcher");
        Shop two = addShopTo(made.merchantId(), "Shop Two");
        Shop three = addShopTo(made.merchantId(), "Shop Three");
        Shop four = addShopTo(made.merchantId(), "Shop Four");

        for (Long shopId : List.of(made.shopId(), two.getId(), three.getId(), four.getId())) {
            mockMvc.perform(get("/api/shop/profile")
                            .header("X-Shop-Id", String.valueOf(shopId))
                            .with(authentication(owner(made.ownerCustomerId()))))
                    .andExpect(status().isOk())
                    // SHOWING SHOP ONE'S DATA WHILE SHOP THREE IS SELECTED is
                    // the exact failure §35 is about, and it is silent: the
                    // merchant edits what they believe is in front of them.
                    .andExpect(jsonPath("$.id").value(shopId));
        }
    }

    @Test
    @DisplayName("my-shops lists all four and marks the one being acted for")
    void theSwitcherGetsItsListFromTheServer() throws Exception {
        var made = onboardOne("Lister");
        Shop two = addShopTo(made.merchantId(), "Shop Two");
        Shop three = addShopTo(made.merchantId(), "Shop Three");

        mockMvc.perform(get("/api/shop/my-shops")
                        .header("X-Shop-Id", String.valueOf(three.getId()))
                        .with(authentication(owner(made.ownerCustomerId()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shops.length()").value(3))
                .andExpect(jsonPath("$.acting").value(three.getId()));

        assertTrue(membership.shopIdsFor(made.ownerCustomerId()).contains(two.getId()));
    }

    // ---------------------------------------------- §50, §93: A, B, and the wall

    @Test
    @DisplayName("merchant A cannot reach merchant B's shop, whatever they put in the header")
    void theWallHoldsFromOutside() throws Exception {
        var a = onboardOne("Merchant A");
        Shop a2 = addShopTo(a.merchantId(), "A Second");
        var b = onboardOne("Merchant B");

        // A -> A1, A -> A2 allowed.
        for (Long mine : List.of(a.shopId(), a2.getId())) {
            mockMvc.perform(get("/api/shop/profile")
                            .header("X-Shop-Id", String.valueOf(mine))
                            .with(authentication(owner(a.ownerCustomerId()))))
                    .andExpect(status().isOk());
        }

        // A -> B1 refused, on every surface a merchant's app touches. The
        // header is the interesting one: it is the ONLY thing the client gets
        // to say about which shop it means, so it is the only lever an
        // attacker has, and TenantResolver.select grants it only when
        // membership already permits.
        for (String path : List.of("/api/shop/profile", "/api/shop/listings",
                "/api/shop/earnings", "/api/shop/staff", "/api/shop/readiness",
                "/api/shop/my-shops")) {
            mockMvc.perform(get(path)
                            .header("X-Shop-Id", String.valueOf(b.shopId()))
                            .with(authentication(owner(a.ownerCustomerId()))))
                    .andExpect(status().isForbidden());
        }

        // And B reaches neither of A's.
        for (Long theirs : List.of(a.shopId(), a2.getId())) {
            mockMvc.perform(get("/api/shop/profile")
                            .header("X-Shop-Id", String.valueOf(theirs))
                            .with(authentication(owner(b.ownerCustomerId()))))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    @DisplayName("a write aimed at another merchant's shop is refused too")
    void theWallIsNotReadOnly() throws Exception {
        var a = onboardOne("Writer A");
        var b = onboardOne("Writer B");

        mockMvc.perform(put("/api/shop/profile")
                        .header("X-Shop-Id", String.valueOf(b.shopId()))
                        .with(authentication(owner(a.ownerCustomerId())))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"Renamed By A Competitor\"}"))
                .andExpect(status().isForbidden());

        assertTrue(!"Renamed By A Competitor"
                        .equals(shops.findById(b.shopId()).orElseThrow().getDisplayName()),
                "the refusal must also mean nothing was written");
    }

    // ------------------------------------------------------------ §97, §42

    @Test
    @DisplayName("a new shop gets its own staff row and leaves the first one alone")
    void openingTheSecondShopDoesNotDisturbTheFirst() {
        var made = onboardOne("Untouched");
        Long first = made.shopId();

        Long firstHomeBefore = jdbc.queryForObject(
                "SELECT shop_id FROM shop_staff WHERE customer_id = ? AND is_default IS TRUE",
                Long.class, made.ownerCustomerId());

        Shop second = addShopTo(made.merchantId(), "Second");

        Integer onSecond = jdbc.queryForObject(
                "SELECT count(*) FROM shop_staff WHERE shop_id = ? AND customer_id = ? "
                        + "AND active IS TRUE", Integer.class, second.getId(),
                made.ownerCustomerId());
        assertEquals(1, onSecond, "the new shop needs somebody who can sign in to it");

        // THE REGRESSION THAT COST A PRODUCTION AFTERNOON. The staff row for
        // the new shop was being written with the OLD shop's id, colliding on
        // uk_shop_staff and taking the whole transaction down.
        Long secondOwner = jdbc.queryForObject(
                "SELECT shop_id FROM shop_staff WHERE shop_id = ? AND customer_id = ?",
                Long.class, second.getId(), made.ownerCustomerId());
        assertEquals(second.getId(), secondOwner);

        Long firstHomeAfter = jdbc.queryForObject(
                "SELECT shop_id FROM shop_staff WHERE customer_id = ? AND is_default IS TRUE",
                Long.class, made.ownerCustomerId());
        assertEquals(firstHomeBefore, firstHomeAfter,
                "opening a branch must not relocate the owner out of the shop they were "
                        + "working in");
        assertEquals(first, firstHomeAfter);
    }
}
