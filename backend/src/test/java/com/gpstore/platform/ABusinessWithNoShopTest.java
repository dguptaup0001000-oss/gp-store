package com.gpstore.platform;

import com.gpstore.entity.Role;
import com.gpstore.exception.ConflictException;
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

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * A merchant who cannot sign in because there is nothing to sign in to.
 *
 * <h2>The real record this was written from</h2>
 *
 * <p>Production, read through the read-only diagnostic:
 *
 * <pre>
 *  id | trading_as        | status      | owner_id      shops for merchant 2: none
 *   2 | GUPT SAREE        | APPLICATION | 993
 * </pre>
 *
 * <p>Merchant row: yes. Owner account: yes. Shops: zero. The owner signed in to
 * Merchant Admin and got {@code "This account is not associated with a shop."}
 *
 * <p>IT WAS NOT A FAILED SHOP CREATION. The id sequences were all ahead of their
 * tables, so nothing had been rolled back or collided; no shop row named
 * merchant 2 at all. The business was created by the console's "Register a
 * business only" action, which opens an ADMIN login and registers the business
 * and deliberately opens no shop - and then hands over a one-time password,
 * which invites exactly the sign-in that dead-ends.
 *
 * <p>So these tests pin both halves: the full onboarding must always produce a
 * shop the owner can actually reach, and a business that legitimately has none
 * must be repairable without deleting it.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("a business with no shop")
class ABusinessWithNoShopTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformOnboardingService onboarding;
    @Autowired private MerchantLifecycleService merchantLifecycle;
    @Autowired private ShopLifecycleService shopLifecycle;
    @Autowired private ShopMembership membership;
    @Autowired private TenantResolver resolver;
    @Autowired private ShopRepository shops;
    @Autowired private PlatformProperties platform;

    private final String tag = "gs" + System.nanoTime();
    private final List<Long> merchantsMade = new ArrayList<>();
    private final List<Long> shopsMade = new ArrayList<>();
    private final List<Long> accountsMade = new ArrayList<>();

    @BeforeEach
    void marketplace() {
        TenantDefaults.install(PlatformMode.MULTI_SHOP_PRODUCTION,
                () -> shops.findByCode(platform.getFirstShopCode()).orElseThrow().getId());
    }

    @AfterEach
    void tidyUp() {
        for (Long shop : shopsMade) {
            jdbc.update("DELETE FROM shop_staff WHERE shop_id = ?", shop);
            jdbc.update("DELETE FROM delivery_pricing_settings WHERE shop_id = ?", shop);
            jdbc.update("DELETE FROM store_operations_settings WHERE shop_id = ?", shop);
            jdbc.update("DELETE FROM shops WHERE id = ?", shop);
        }
        for (Long merchant : merchantsMade) {
            jdbc.update("DELETE FROM shops WHERE merchant_id = ?", merchant);
            jdbc.update("DELETE FROM merchants WHERE id = ?", merchant);
        }
        for (Long account : accountsMade) {
            jdbc.update("DELETE FROM shop_staff WHERE customer_id = ?", account);
            jdbc.update("DELETE FROM customers WHERE id = ?", account);
        }
        TenantDefaults.install(platform.getMode(),
                () -> shops.findByCode(platform.getFirstShopCode()).orElseThrow().getId());
    }

    // ================================================== the normal way in

    @Test
    @DisplayName("full onboarding always leaves a shop its owner can reach")
    void onboardingProducesAUsableShop() {
        PlatformOnboardingService.OnboardedMerchant opened = onboard("Full " + tag);

        // 1-4. Merchant, owner account and shop all exist.
        assertNotNull(opened.merchantId(), "a merchant");
        assertNotNull(opened.ownerCustomerId(), "an owner account");
        assertNotNull(opened.shopId(), "AND a shop - this is the whole point");

        // 5. The shop belongs to THAT merchant, read from the database.
        Long ownerOfShop = jdbc.queryForObject(
                "SELECT merchant_id FROM shops WHERE id = ?", Long.class, opened.shopId());
        assertEquals(opened.merchantId(), ownerOfShop, "the shop must be under its own merchant");

        // 6. The owner is authorised on it, and it is their home shop.
        assertTrue(membership.permits(opened.ownerCustomerId(), opened.shopId()),
                "the owner must be able to work in the shop that was just opened for them");
        assertEquals(opened.shopId(),
                membership.defaultShopIdFor(opened.ownerCustomerId()).orElse(null),
                "and it must be their default, or the app has nowhere to open");

        // 7-8. Signing in with no shop named resolves to exactly that shop.
        TenantScope scope = asAccount(opened.ownerCustomerId(), () -> resolver.select(null));
        assertTrue(scope.isSingleShop(), "one shop means one answer");
        assertEquals(opened.shopId(), scope.shopId(),
                "a one-shop merchant must land in their own shop automatically");
    }

    @Test
    @DisplayName("the onboarded shop starts empty - nothing is inherited from anybody")
    void nothingIsCopiedIn() {
        PlatformOnboardingService.OnboardedMerchant opened = onboard("Empty " + tag);

        assertEquals(0, count("shop_product_variants", opened.shopId()),
                "a new shop must not inherit Shop #1's listings");
        assertEquals(0, count("shop_categories", opened.shopId()),
                "nor anybody's departments");
        assertEquals(0, count("inventory", opened.shopId()), "nor anybody's stock");
        assertEquals(0, count("orders", opened.shopId()), "nor anybody's orders");
        assertEquals(1, count("shop_staff", opened.shopId()),
                "exactly its own owner, and nobody else's staff");
    }

    // ============================================== the state to repair

    @Nested
    @DisplayName("a business registered without one")
    class RegisteredWithoutAShop {

        @Test
        @DisplayName("reproduces GUPT SAREE exactly: merchant, owner, no shop")
        void reproducesTheRealRecord() {
            long owner = account("owner");
            Long merchant = registerOnly("Gupt " + tag, owner);

            assertEquals("APPLICATION",
                    jdbc.queryForObject("SELECT status FROM merchants WHERE id = ?",
                            String.class, merchant),
                    "the register-only path leaves the business in APPLICATION");
            assertEquals(0, (int) jdbc.queryForObject(
                    "SELECT count(*) FROM shops WHERE merchant_id = ?", Integer.class, merchant),
                    "and opens no shop - which is the production state, reproduced");

            // AND THE SIGN-IN DEAD-ENDS, which is what the merchant saw.
            assertThrows(IllegalStateException.class,
                    () -> asAccount(owner, () -> resolver.select(null)),
                    "an account with no membership must resolve to no shop - never to Shop #1");
        }

        @Test
        @DisplayName("add-first-shop repairs it without deleting anything")
        void addFirstShopRepairsIt() {
            long owner = account("fix");
            Long merchant = registerOnly("Fixable " + tag, owner);

            PlatformOnboardingService.OnboardedShop made = onboarding.addFirstShop(
                    merchant, "fix-" + tag, "Fixable Saree",
                    26.76, 83.37, new BigDecimal("8"), "Asia/Kolkata");
            shopsMade.add(made.shopId());

            // 13. The merchant is intact - same row, not recreated.
            assertEquals(merchant, made.merchantId(), "the SAME business, repaired in place");
            assertEquals("APPLICATION", made.merchantStatusBefore());
            assertEquals("APPROVED", made.merchantStatusAfter(),
                    "approved so it may hold a shop, and the console is told it happened");

            // The shop is real, owned, and reachable.
            assertEquals(merchant, jdbc.queryForObject(
                    "SELECT merchant_id FROM shops WHERE id = ?", Long.class, made.shopId()));
            assertTrue(membership.permits(owner, made.shopId()),
                    "the owner must now be able to work in it");

            TenantScope scope = asAccount(owner, () -> resolver.select(null));
            assertEquals(made.shopId(), scope.shopId(),
                    "and signing in must now land in it");
        }

        @Test
        @DisplayName("the repaired shop is DRAFT, so approving did not put it in front of customers")
        void approvingIsNotTrading() {
            long owner = account("draft");
            Long merchant = registerOnly("Draft " + tag, owner);

            PlatformOnboardingService.OnboardedShop made = onboarding.addFirstShop(
                    merchant, "draft-" + tag, null,
                    26.76, 83.37, new BigDecimal("8"), null);
            shopsMade.add(made.shopId());

            assertEquals("DRAFT", jdbc.queryForObject(
                    "SELECT status FROM shops WHERE id = ?", String.class, made.shopId()),
                    "a brand-new shop must not be trading - APPROVED is a business status, "
                            + "not a shopfront");
            assertEquals(0, count("shop_product_variants", made.shopId()),
                    "and it must arrive with empty shelves");
        }

        @Test
        @DisplayName("it refuses a business that already has a shop")
        void onlyEverTheFirst() {
            PlatformOnboardingService.OnboardedMerchant opened = onboard("Twice " + tag);

            ConflictException refused = assertThrows(ConflictException.class,
                    () -> onboarding.addFirstShop(opened.merchantId(), "second-" + tag, null,
                            26.76, 83.37, new BigDecimal("8"), null));
            assertTrue(refused.getMessage().toLowerCase().contains("already has a shop"),
                    "and says why: " + refused.getMessage());
        }

        @Test
        @DisplayName("it refuses a business with no owner, rather than opening a shop nobody can reach")
        void anOwnerlessBusinessIsRefused() {
            Long merchant = merchantLifecycle.register(
                    "Ownerless " + tag, "Ownerless", null, null, null, true).getId();
            merchantsMade.add(merchant);

            assertThrows(com.gpstore.exception.BadRequestException.class,
                    () -> onboarding.addFirstShop(merchant, "own-" + tag, null,
                            26.76, 83.37, new BigDecimal("8"), null),
                    "a shop whose owner cannot sign in is the bug, not the fix");
        }

        @Test
        @DisplayName("repairing one business does not touch another's")
        void repairIsNotContagious() {
            PlatformOnboardingService.OnboardedMerchant neighbour = onboard("Neighbour " + tag);
            int theirListings = count("shop_product_variants", neighbour.shopId());
            int theirStaff = count("shop_staff", neighbour.shopId());

            long owner = account("quiet");
            Long merchant = registerOnly("Quiet " + tag, owner);
            PlatformOnboardingService.OnboardedShop made = onboarding.addFirstShop(
                    merchant, "quiet-" + tag, null, 26.76, 83.37, new BigDecimal("8"), null);
            shopsMade.add(made.shopId());

            assertEquals(theirListings, count("shop_product_variants", neighbour.shopId()),
                    "the neighbour's shelves must be exactly as they were");
            assertEquals(theirStaff, count("shop_staff", neighbour.shopId()),
                    "and so must their staff");
            assertFalse(membership.permits(owner, neighbour.shopId()),
                    "and the repaired merchant must have gained nothing next door");
        }
    }

    // ===================================================== who reaches what

    @Nested
    @DisplayName("one merchant never reaches another's shop")
    class NeverSomebodyElsesShop {

        @Test
        @DisplayName("A may enter A, B may enter B, neither may enter the other")
        void theFourWayCheck() {
            PlatformOnboardingService.OnboardedMerchant a = onboard("A " + tag);
            PlatformOnboardingService.OnboardedMerchant b = onboard("B " + tag);

            assertTrue(membership.permits(a.ownerCustomerId(), a.shopId()), "A -> A allowed");
            assertTrue(membership.permits(b.ownerCustomerId(), b.shopId()), "B -> B allowed");
            assertFalse(membership.permits(a.ownerCustomerId(), b.shopId()), "A -> B denied");
            assertFalse(membership.permits(b.ownerCustomerId(), a.shopId()), "B -> A denied");
        }

        @Test
        @DisplayName("naming the other shop in X-Shop-Id does not get you in")
        void theHeaderIsNotAKey() {
            PlatformOnboardingService.OnboardedMerchant a = onboard("Hdr A " + tag);
            PlatformOnboardingService.OnboardedMerchant b = onboard("Hdr B " + tag);

            assertThrows(IllegalStateException.class,
                    () -> asAccount(a.ownerCustomerId(), () -> resolver.select(b.shopId())),
                    "selecting a shop you are not staff of must be refused, whatever the "
                            + "client asked for");
            // And the legitimate one still works, so this is a real check and
            // not a blanket refusal.
            assertEquals(a.shopId(),
                    asAccount(a.ownerCustomerId(), () -> resolver.select(a.shopId())).shopId());
        }

        @Test
        @DisplayName("a merchant with two shops switches between their own, and only their own")
        void twoShopsOneMerchant() {
            PlatformOnboardingService.OnboardedMerchant a = onboard("Two " + tag);
            PlatformOnboardingService.OnboardedMerchant other = onboard("Other " + tag);

            long second = shopLifecycle.open(a.merchantId(), "two2-" + tag, "Second",
                    26.76, 83.37, new BigDecimal("8"), null).getId();
            shopsMade.add(second);
            // NO MANUAL MEMBERSHIP ROW. Opening a shop under a merchant already
            // makes that merchant's owner staff of it - the first draft of this
            // test inserted one by hand and hit uk_shop_staff, which is the
            // system saying the row was there. Asserted rather than assumed:
            assertTrue(membership.permits(a.ownerCustomerId(), second),
                    "opening a second shop under a business must let its owner work in it");

            List<Long> theirs = membership.shopIdsFor(a.ownerCustomerId());
            assertEquals(2, theirs.size(), "two shops, so the app offers a switch");
            assertTrue(theirs.contains(a.shopId()) && theirs.contains(second));
            assertFalse(theirs.contains(other.shopId()),
                    "and the switcher must never list a shop belonging to somebody else");

            // Both of their own resolve; the stranger's does not.
            assertEquals(second,
                    asAccount(a.ownerCustomerId(), () -> resolver.select(second)).shopId());
            assertThrows(IllegalStateException.class,
                    () -> asAccount(a.ownerCustomerId(), () -> resolver.select(other.shopId())));
        }
    }

    // ======================================================== the console

    @Test
    @DisplayName("the console route opens the first shop, and a merchant may not call it")
    void theRouteIsThePlatformOwners() throws Exception {
        long owner = account("route");
        Long merchant = registerOnly("Route " + tag, owner);
        String json = """
                {"shopCode":"route-%s","displayName":"Route Saree","latitude":26.76,
                 "longitude":83.37,"maxDeliveryRadiusKm":8,"timeZone":"Asia/Kolkata"}
                """.formatted(tag);

        // A shopkeeper holds ADMIN, not PLATFORM_ADMIN.
        MvcResult asMerchant = send(
                post("/api/platform/merchants/" + merchant + "/first-shop"),
                owner, Role.ADMIN, json);
        assertEquals(403, asMerchant.getResponse().getStatus(),
                "opening shops under any business is the platform owner's, not a merchant's");

        MvcResult asOwner = send(
                post("/api/platform/merchants/" + merchant + "/first-shop"),
                owner, Role.SUPER_ADMIN, json);
        assertEquals(200, asOwner.getResponse().getStatus(),
                "and the platform owner may: " + asOwner.getResponse().getContentAsString());

        Long made = jdbc.queryForObject(
                "SELECT id FROM shops WHERE merchant_id = ?", Long.class, merchant);
        shopsMade.add(made);
        assertTrue(membership.permits(owner, made),
                "and the owner can now work in it");
    }

    // ========================================================== fixtures

    private PlatformOnboardingService.OnboardedMerchant onboard(String business) {
        PlatformOnboardingService.OnboardedMerchant opened = onboarding.onboard(
                business, "Owner of " + business, business.replaceAll("\\s+", "") + "@example.test",
                null, null, 26.76, 83.37, new BigDecimal("8"), "Asia/Kolkata", true);
        merchantsMade.add(opened.merchantId());
        shopsMade.add(opened.shopId());
        accountsMade.add(opened.ownerCustomerId());
        return opened;
    }

    /** The console's "Register a business only" path, which is how GUPT SAREE was made. */
    private Long registerOnly(String business, long owner) {
        Long id = merchantLifecycle.register(business, business, null, null, owner, true).getId();
        merchantsMade.add(id);
        return id;
    }

    private long account(String kind) {
        String email = tag + "-" + kind + "@example.test";
        jdbc.update("""
                INSERT INTO customers (full_name, email, mobile_number, password, role, active)
                VALUES (?, ?, ?, 'not-a-real-hash', 'ADMIN', true)
                """, "Owner " + kind, email,
                "9" + (100000000 + (int) (Math.random() * 899999999)));
        Long id = jdbc.queryForObject("SELECT id FROM customers WHERE email = ?", Long.class, email);
        accountsMade.add(id);
        return id;
    }

    private int count(String table, Long shopId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE shop_id = ?", Integer.class, shopId);
    }

    /**
     * Runs something as one account, with no shop scope already on the thread.
     *
     * <p>The scope is what is being tested, so leaving a stale one behind would
     * make the next assertion meaningless.
     */
    private <T> T asAccount(Long accountId, java.util.function.Supplier<T> work) {
        TenantContext.clear();
        org.springframework.security.core.context.SecurityContextHolder.getContext()
                .setAuthentication(tokenFor(accountId, Role.ADMIN));
        try {
            return work.get();
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
            TenantContext.clear();
        }
    }

    private MvcResult send(MockHttpServletRequestBuilder request, Long accountId,
                           Role role, String json) throws Exception {
        request.with(authentication(tokenFor(accountId, role)));
        if (json != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json);
        }
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
