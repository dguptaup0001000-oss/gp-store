package com.gpstore.platform;

import com.gpstore.entity.Role;
import com.gpstore.security.AuthenticatedUser;
import com.gpstore.security.RolePermissions;
import com.gpstore.security.WithStaff;
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

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * The lifecycle rules, and what a single-shop deployment still does.
 *
 * TWO THINGS AT ONCE, on purpose. The transition tables are the same code
 * whichever mode the platform runs in, so they are tested here where the
 * context is shared with the rest of the suite. And the half of Slice 3 that
 * MUST NOT change anything - the shop that is trading today keeps editing its
 * own catalogue, and its owner is still refused the platform console - is only
 * meaningful in the single-shop configuration the shop actually runs.
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
@DisplayName("Merchant and shop lifecycle, and single-shop behaviour that must not move")
class MerchantShopLifecycleTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MerchantLifecycleService merchantLifecycle;
    @Autowired private ShopLifecycleService shopLifecycle;
    @Autowired private ShopTradingGate tradingGate;
    @Autowired private ShopRepository shops;
    @Autowired private MerchantRepository merchants;
    @Autowired private PlatformProperties platform;

    private final String tag = "life" + System.nanoTime();
    private long shopOne;
    private ShopStatus shopOneStatusBefore;
    private Long platformAdminId;

    @BeforeEach
    void setUp() {
        shopOne = shops.findByCode(platform.getFirstShopCode()).orElseThrow().getId();
        shopOneStatusBefore = shops.findById(shopOne).orElseThrow().getStatus();
        platformAdminId = newAccount(Role.PLATFORM_ADMIN);
    }

    @AfterEach
    void putShopOneBack() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
        // The shop that is actually trading. A test that leaves it paused
        // takes every later test's checkout down with it.
        Shop shop = shops.findById(shopOne).orElseThrow();
        if (shop.getStatus() != shopOneStatusBefore) {
            shop.setStatus(shopOneStatusBefore);
            shops.save(shop);
        }
        jdbc.update("DELETE FROM shop_staff WHERE customer_id = ?", platformAdminId);
        jdbc.update("DELETE FROM customers WHERE id = ?", platformAdminId);
        jdbc.update("DELETE FROM store_operations_settings WHERE shop_id IN "
                + "(SELECT id FROM shops WHERE code like ?)", "LIFE-" + tag + "%");
        jdbc.update("DELETE FROM delivery_pricing_settings WHERE shop_id IN "
                + "(SELECT id FROM shops WHERE code like ?)", "LIFE-" + tag + "%");
        jdbc.update("DELETE FROM shops WHERE code like ?", "LIFE-" + tag + "%");
        jdbc.update("DELETE FROM merchants WHERE legal_name like ?", "Lifecycle " + tag + "%");
    }

    // ------------------------------------------------- 1. merchant lifecycle

    @Test
    @DisplayName("a merchant walks its lifecycle and cannot skip the review")
    void merchantLifecycleIsAWalkNotAJump() {
        Merchant m = merchantLifecycle.register("Lifecycle " + tag, null, null, null, null, true);

        assertEquals(MerchantStatus.APPLICATION, m.getStatus(),
                "a merchant created straight into trading is one nobody checked");

        assertThrows(RuntimeException.class,
                () -> merchantLifecycle.transition(m.getId(), MerchantStatus.ACTIVE, "skip ahead"),
                "APPLICATION to ACTIVE would mean the status column records a review that "
                        + "never happened");

        merchantLifecycle.transition(m.getId(), MerchantStatus.PENDING_REVIEW, "submitted");
        merchantLifecycle.transition(m.getId(), MerchantStatus.APPROVED, "documents checked");
        Merchant active = merchantLifecycle.transition(m.getId(), MerchantStatus.ACTIVE, "trading");

        assertEquals(MerchantStatus.ACTIVE, active.getStatus());
        assertTrue(active.getStatusReason().contains("trading"),
                "every move is recorded with the reason for it (§21)");
    }

    @Test
    @DisplayName("no status change without a reason")
    void everyMoveNeedsAReason() {
        Merchant m = merchantLifecycle.register("Lifecycle " + tag + " r", null, null, null, null, true);

        assertThrows(RuntimeException.class,
                () -> merchantLifecycle.transition(m.getId(), MerchantStatus.PENDING_REVIEW, "  "),
                "a suspension with no reason is a decision nobody can review or appeal");
    }

    @Test
    @DisplayName("the transition tables are closed, not open")
    void transitionTablesAreExhaustive() {
        // A lifecycle where everything can become everything is a free-text
        // field wearing an enum's clothes.
        assertTrue(MerchantStatus.REMOVED.allowedNext().isEmpty());
        assertTrue(MerchantStatus.REJECTED.allowedNext().isEmpty());
        assertTrue(ShopStatus.CLOSED.allowedNext().isEmpty());

        assertFalse(MerchantStatus.APPLICATION.canMoveTo(MerchantStatus.ACTIVE));
        assertTrue(MerchantStatus.SUSPENDED.canMoveTo(MerchantStatus.ACTIVE),
                "a suspension that could only be undone by re-onboarding would cost a shopkeeper "
                        + "their whole history over a fixable problem");

        assertFalse(ShopStatus.SUSPENDED.isMerchantChoice(ShopStatus.ACTIVE),
                "a shop that can clear its own suspension has not been suspended");
        assertTrue(ShopStatus.ACTIVE.isMerchantChoice(ShopStatus.PAUSED),
                "a holiday is the shopkeeper's own business");
    }

    // ---------------------------------------------------- 2. shop lifecycle

    @Test
    @DisplayName("a shop opens as a draft and its owner joins its staff automatically")
    void openingAShopGivesItAFirstStaffMember() {
        Long owner = newAccount(Role.ADMIN);
        try {
            Merchant m = merchantLifecycle.register("Lifecycle " + tag + " s", null, null, null, owner, true);
            merchantLifecycle.transition(m.getId(), MerchantStatus.PENDING_REVIEW, "submitted");
            merchantLifecycle.transition(m.getId(), MerchantStatus.APPROVED, "checked");

            Shop shop = shopLifecycle.open(m.getId(), "LIFE-" + tag, "Lifecycle shop",
                    12.9, 77.6, new BigDecimal("5"), "Asia/Kolkata");

            assertEquals(ShopStatus.DRAFT, shop.getStatus(),
                    "a storefront that appears live the moment it is created is one nobody set up");
            assertEquals(1, jdbc.queryForObject(
                    "SELECT count(*) FROM shop_staff WHERE shop_id = ? AND customer_id = ?",
                    Integer.class, shop.getId(), owner),
                    "a brand new shop nobody can sign in to is a shop nobody can open");
        } finally {
            // Children before parents: merchants.owner_customer_id points at
            // this account, and shop_staff points at both.
            jdbc.update("DELETE FROM shop_staff WHERE customer_id = ?", owner);
            jdbc.update("DELETE FROM store_operations_settings WHERE shop_id IN "
                + "(SELECT id FROM shops WHERE code like ?)", "LIFE-" + tag + "%");
        jdbc.update("DELETE FROM delivery_pricing_settings WHERE shop_id IN "
                + "(SELECT id FROM shops WHERE code like ?)", "LIFE-" + tag + "%");
        jdbc.update("DELETE FROM shops WHERE code like ?", "LIFE-" + tag + "%");
            jdbc.update("DELETE FROM merchants WHERE owner_customer_id = ?", owner);
            jdbc.update("DELETE FROM customers WHERE id = ?", owner);
        }
    }

    // -------------------------------------------------- 17. trading gate

    @Test
    @DisplayName("a paused shop stops taking orders")
    void aPausedShopCannotTrade() {
        TenantContext.runWithin(TenantScope.ofShop(shopOne), () -> {
            assertDoesNotThrow(tradingGate::requireCanAcceptOrders,
                    "the trading shop must be able to trade");
            return null;
        });

        shopLifecycle.transitionAsPlatform(shopOne, ShopStatus.PAUSED, "closed for a wedding");

        TenantContext.runWithin(TenantScope.ofShop(shopOne), () -> {
            assertThrows(RuntimeException.class, tradingGate::requireCanAcceptOrders,
                    "a paused storefront must not take a customer's money");
            return null;
        });
    }

    @Test
    @DisplayName("checkout itself is behind the gate, not just the gate's own test")
    void checkoutRefusesWhenTheShopIsPaused() throws Exception {
        Long customerId = newAccount(Role.CUSTOMER);
        try {
            shopLifecycle.transitionAsPlatform(shopOne, ShopStatus.PAUSED, "closed for a wedding");

            int status = mockMvc.perform(post("/api/orders/place")
                            .contentType(MediaType.APPLICATION_JSON)
                            // A valid-shaped body: the address id is @NotNull,
                            // and bean validation runs BEFORE the controller, so
                            // an empty object would 400 without ever reaching
                            // the gate and prove nothing. This id belongs to
                            // nobody, which is fine - the shop being shut is
                            // checked first, and that is the assertion.
                            .content("{\"addressId\":999999999,\"paymentMethod\":\"COD\"}")
                            .header("Idempotency-Key", "gate-" + tag)
                            .with(authentication(tokenFor(customerId, Role.CUSTOMER))))
                    .andReturn().getResponse().getStatus();

            // 409, not 400: the shop being shut is checked before the cart is,
            // which is what proves the gate is on the checkout path rather
            // than only in its own unit test.
            assertEquals(409, status,
                    "checkout went past a paused shop and on to the cart");
        } finally {
            jdbc.update("DELETE FROM customers WHERE id = ?", customerId);
        }
    }

    // ----------------------- 10-12. what a single shop must keep being able to do

    @Test
    @WithStaff
    @DisplayName("under one shop, the shopkeeper still edits the catalogue")
    void singleShopCatalogueEditingIsUnchanged() throws Exception {
        // THE HALF OF THIS SLICE THAT MUST CHANGE NOTHING. With one merchant
        // the shopkeeper IS the platform; taking catalogue editing away from
        // them would break the shop that is actually trading today.
        int status = mockMvc.perform(post("/api/categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Lifecycle category " + tag + "\",\"active\":true,\"gstRate\":5}"))
                .andReturn().getResponse().getStatus();

        assertNotEquals(403, status,
                "the trading shop's own admin was refused a catalogue write they have always had");
        jdbc.update("DELETE FROM categories WHERE name = ?", "Lifecycle category " + tag);
    }

    @Test
    @WithStaff
    @DisplayName("even under one shop, a shop admin cannot reach the platform console")
    void theShopOwnerIsStillNotAPlatformOperator() throws Exception {
        assertEquals(403, mockMvc.perform(get("/api/platform/merchants"))
                .andReturn().getResponse().getStatus(),
                "the two jobs are separate in every mode, or the separation is decorative");
    }

    @Test
    @DisplayName("a platform admin can reach the platform console")
    void thePlatformOperatorCan() throws Exception {
        assertEquals(200, mockMvc.perform(get("/api/platform/merchants")
                        .with(authentication(tokenFor(platformAdminId, Role.PLATFORM_ADMIN))))
                .andReturn().getResponse().getStatus());
    }

    @Test
    @WithStaff
    @DisplayName("the shopkeeper's own price list is theirs, in every mode")
    void theShopCanReadItsOwnListings() throws Exception {
        assertEquals(200, mockMvc.perform(get("/api/shop/listings?page=0&size=5"))
                .andReturn().getResponse().getStatus());
        assertEquals(200, mockMvc.perform(get("/api/shop/profile"))
                .andReturn().getResponse().getStatus());
    }

    // ------------------------------------------------------------ fixtures

    private Long newAccount(Role role) {
        String email = tag + "-" + role.name().toLowerCase(java.util.Locale.ROOT)
                + "-" + System.nanoTime() + "@example.test";
        jdbc.update("""
                INSERT INTO customers (full_name, email, mobile_number, password, role, active)
                VALUES (?, ?, ?, 'not-a-real-hash', ?, true)
                """, role.name() + " " + tag, email,
                "9" + (100000000 + (int) (Math.random() * 899999999)), role.name());
        return jdbc.queryForObject("SELECT id FROM customers WHERE email = ?", Long.class, email);
    }

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
