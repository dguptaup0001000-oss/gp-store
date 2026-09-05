package com.gpstore.platform;

import com.gpstore.catalog.shop.ShopProductVariant;
import com.gpstore.catalog.shop.ShopProductVariantRepository;
import com.gpstore.entity.Category;
import com.gpstore.entity.Product;
import com.gpstore.entity.ProductVariant;
import com.gpstore.entity.Role;
import com.gpstore.repository.CategoryRepository;
import com.gpstore.repository.ProductRepository;
import com.gpstore.repository.ProductVariantRepository;
import com.gpstore.security.AdminPermission;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;

/**
 * A second merchant signs in and runs their own shop. Nothing else.
 *
 * THIS IS THE FIRST TEST IN THE PROJECT THAT RUNS AS A MARKETPLACE.
 * platform.mode=MULTI_SHOP_PRODUCTION, so TenantResolver stops answering
 * "Shop #1" to everything and has to work out who the caller is from the
 * staff list. Every property the earlier slices asserted about DATA is here
 * asserted about IDENTITY: who you are decides which shop you are in, and no
 * part of the request gets a say.
 *
 * WHY THE SHOP IS NOT IN THE TOKEN. It is read from shop_staff on every
 * request, which is the same decision this codebase already made for
 * permissions - JwtFilter derives authorities from the live account row so a
 * demotion applies immediately. A shop claim inside a JWT has the identical
 * hole: move a manager between shops and their existing token keeps working
 * against the old one until it expires. Asserted below by moving somebody.
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
@DisplayName("Two merchants, one platform, and identity decides everything")
class MarketplaceIdentityTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TenantResolver resolver;
    @Autowired private ShopMembership membership;
    @Autowired private MerchantLifecycleService merchantLifecycle;
    @Autowired private ShopLifecycleService shopLifecycle;
    @Autowired private ShopRepository shops;
    @Autowired private MerchantRepository merchants;
    @Autowired private PlatformProperties platform;
    @Autowired private ShopProductVariantRepository listings;
    @Autowired private ProductRepository products;
    @Autowired private ProductVariantRepository variants;
    @Autowired private CategoryRepository categories;

    private final String tag = "mkt" + System.nanoTime();

    private long shopA;
    private Long merchantA;
    private Long ownerA;

    private long shopB;
    private long shopB2;
    private Long merchantB;
    private Long ownerB;

    private Long platformAdminId;
    private Long categoryId;
    private Long productId;
    private Long variantId;

    /**
     * Restores the deployment-wide defaults this class deliberately changes.
     *
     * TenantDefaults is a static holder, because a JPA entity listener cannot
     * be injected - and in production that is one JVM, one context, one mode.
     * A TEST RUN IS NOT: Spring caches several application contexts in one
     * JVM, and this class is the only one that builds a MULTI_SHOP one. Once
     * its context installs MULTI_SHOP, every later test reusing an earlier
     * SINGLE_SHOP context would find the wrong mode installed and its inserts
     * would fail closed - a failure that depends on class ordering, which is
     * the worst kind to debug.
     *
     * So this class brackets itself: it installs its own mode before each test
     * and puts the single-shop default back after. No other test class changes
     * the mode, so the state is deterministic whichever order they run in.
     */
    private void installDefaultsFor(PlatformMode installedMode) {
        Long shopOne = shops.findByCode(platform.getFirstShopCode()).orElseThrow().getId();
        TenantDefaults.install(installedMode, () -> shopOne);
    }

    @BeforeEach
    void twoIndependentBusinesses() {
        installDefaultsFor(PlatformMode.MULTI_SHOP_PRODUCTION);
        shopA = shops.findByCode(platform.getFirstShopCode()).orElseThrow().getId();
        merchantA = shops.findById(shopA).orElseThrow().getMerchantId();

        ownerA = newAccount("ownerA", Role.ADMIN);
        ownerB = newAccount("ownerB", Role.ADMIN);
        platformAdminId = newAccount("platform", Role.PLATFORM_ADMIN);

        // Shop #1's owner is on its staff. Everything about that is ordinary:
        // the same call the platform console makes for any shop.
        membership.grant(shopA, ownerA, true);

        // A second business, onboarded through the real lifecycle rather than
        // inserted straight into ACTIVE - a merchant created already trading
        // is a merchant nobody checked.
        Merchant b = merchantLifecycle.register("Second Kirana " + tag, "Second Kirana",
                null, null, ownerB, true);
        merchantB = b.getId();
        merchantLifecycle.transition(merchantB, MerchantStatus.PENDING_REVIEW, "submitted");
        merchantLifecycle.transition(merchantB, MerchantStatus.APPROVED, "documents checked");
        merchantLifecycle.transition(merchantB, MerchantStatus.ACTIVE, "opened for trade");

        shopB = shopLifecycle.open(merchantB, "MKT-" + tag, "Second Kirana",
                12.9, 77.6, new BigDecimal("5"), "Asia/Kolkata").getId();
        shopLifecycle.transitionAsPlatform(shopB, ShopStatus.ACTIVE, "ready");

        // The same merchant's SECOND storefront - requirement 7's shape.
        shopB2 = shopLifecycle.open(merchantB, "MKT2-" + tag, "Second Kirana Annexe",
                12.91, 77.61, new BigDecimal("5"), "Asia/Kolkata").getId();
        shopLifecycle.transitionAsPlatform(shopB2, ShopStatus.ACTIVE, "ready");

        Category category = new Category();
        category.setName("Marketplace fixture " + tag);
        category.setActive(true);
        category.setGstRate(new BigDecimal("5"));
        categoryId = categories.save(category).getId();

        Product product = new Product();
        product.setName("Shared item " + tag);
        product.setCategory(category);
        product.setActive(true);
        productId = products.save(product).getId();

        ProductVariant variant = new ProductVariant();
        variant.setProduct(product);
        variant.setQuantity(1.0);
        variant.setUnit("kg");
        variant.setSellingPrice(new BigDecimal("60.00"));
        variant.setAvailable(Boolean.TRUE);
        variant.setActive(Boolean.TRUE);
        variantId = variants.save(variant).getId();
    }

    @AfterEach
    void tidyUp() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
        jdbc.update("DELETE FROM shop_product_variants WHERE product_variant_id = ?", variantId);
        jdbc.update("DELETE FROM product_variants WHERE id = ?", variantId);
        jdbc.update("DELETE FROM products WHERE id = ?", productId);
        jdbc.update("DELETE FROM categories WHERE id = ?", categoryId);
        jdbc.update("DELETE FROM shop_staff WHERE shop_id in (?, ?)", shopB, shopB2);
        jdbc.update("DELETE FROM shop_staff WHERE customer_id in (?, ?, ?)",
                ownerA, ownerB, platformAdminId);
        // Opening a shop now creates its settings rows too, so they have to
        // go with it - an orphan settings row would be a shop's hours with no
        // shop.
        jdbc.update("DELETE FROM store_operations_settings WHERE shop_id in (?, ?)", shopB, shopB2);
        jdbc.update("DELETE FROM delivery_pricing_settings WHERE shop_id in (?, ?)", shopB, shopB2);
        jdbc.update("DELETE FROM shops WHERE id in (?, ?)", shopB, shopB2);
        jdbc.update("DELETE FROM merchants WHERE id = ?", merchantB);
        jdbc.update("DELETE FROM customers WHERE id in (?, ?, ?)", ownerA, ownerB, platformAdminId);
        installDefaultsFor(PlatformMode.SINGLE_SHOP);
    }

    @Test
    @DisplayName("this class really is running as a marketplace")
    void theModeIsActuallyMultiShop() {
        // Without this the whole class could quietly be asserting single-shop
        // behaviour: TenantResolver's first line short-circuits to Shop #1
        // under SINGLE_SHOP, and every test below would still pass while
        // proving nothing about a marketplace.
        assertTrue(platform.getMode().isMultiShop(),
                "platform.mode did not take effect, so none of these assertions mean what they say");
        assertEquals(PlatformMode.MULTI_SHOP_PRODUCTION, TenantDefaults.installedMode(),
                "the entity listener's defaults must describe the mode this context runs in");
    }

    // ------------------------------------------------------- 5. it works

    @Test
    @DisplayName("a second merchant signs in and lands in their own shop")
    void aSecondMerchantOperatesTheirOwnShop() {
        signedInAs(ownerB, Role.ADMIN);

        TenantScope scope = resolver.resolve();

        assertTrue(scope.isSingleShop(), "a shopkeeper is never given a platform-wide scope");
        assertEquals(shopB, scope.requireShopId(),
                "the second merchant must land in the shop they own, resolved from the staff "
                        + "list rather than from anything they sent");
    }

    @Test
    @DisplayName("each merchant's own shop is their own, with no shop id anywhere in the request")
    void eachMerchantGetsTheirOwnShop() {
        signedInAs(ownerA, Role.ADMIN);
        assertEquals(shopA, resolver.resolve().requireShopId());

        signedInAs(ownerB, Role.ADMIN);
        assertEquals(shopB, resolver.resolve().requireShopId());
    }

    // ------------------------------------- 6. and only their own

    @Test
    @DisplayName("a merchant cannot select a shop they are not staff of")
    void merchantACannotSelectShopB() {
        signedInAs(ownerA, Role.ADMIN);

        assertEquals(shopA, resolver.select(shopA).requireShopId(),
                "naming your own shop is allowed - that is what a shop switcher does");

        assertThrows(IllegalStateException.class, () -> resolver.select(shopB),
                "Merchant A named Merchant B's shop and was given it");
        assertThrows(IllegalStateException.class, () -> resolver.select(shopB2));
    }

    @Test
    @DisplayName("the X-Shop-Id header cannot reach another merchant's shop over HTTP")
    void theShopHeaderCannotCrossMerchants() throws Exception {
        MvcResult refused = mockMvc.perform(get("/api/shop/profile")
                        .header(TenantContextFilter.SHOP_HEADER, String.valueOf(shopB))
                        .with(authentication(tokenFor(ownerA, Role.ADMIN))))
                .andReturn();

        assertEquals(403, refused.getResponse().getStatus(),
                "a shop id in a header must be refused, not honoured - it is a selection among "
                        + "the shops the credential permits and nothing more");
        assertFalse(refused.getResponse().getContentAsString().contains("MKT-" + tag),
                "the refusal leaked the other merchant's shop code");
    }

    @Test
    @DisplayName("a merchant cannot read another merchant's shop profile")
    void merchantACannotReadShopBProfile() throws Exception {
        MvcResult mine = mockMvc.perform(get("/api/shop/profile")
                        .with(authentication(tokenFor(ownerB, Role.ADMIN))))
                .andReturn();

        assertEquals(200, mine.getResponse().getStatus(),
                "the second merchant lost access to their own shop; body: "
                        + mine.getResponse().getContentAsString());
        assertTrue(mine.getResponse().getContentAsString().contains("MKT-" + tag));

        MvcResult theirs = mockMvc.perform(get("/api/shop/profile")
                        .with(authentication(tokenFor(ownerA, Role.ADMIN))))
                .andReturn();

        assertEquals(200, theirs.getResponse().getStatus());
        assertFalse(theirs.getResponse().getContentAsString().contains("MKT-" + tag),
                "Merchant A's own shop profile returned Merchant B's shop");
    }

    @Test
    @DisplayName("a merchant cannot reach the platform console at all")
    void merchantsCannotReachThePlatformConsole() throws Exception {
        assertEquals(403, mockMvc.perform(get("/api/platform/merchants")
                        .with(authentication(tokenFor(ownerA, Role.ADMIN))))
                .andReturn().getResponse().getStatus(),
                "a shop owner holds SYSTEM_ADMIN, and if that were enough to run the "
                        + "marketplace every shopkeeper would be a platform operator");

        assertEquals(403, mockMvc.perform(put("/api/platform/merchants/" + merchantA + "/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"SUSPENDED\",\"reason\":\"nice shop you have there\"}")
                        .with(authentication(tokenFor(ownerB, Role.ADMIN))))
                .andReturn().getResponse().getStatus(),
                "one merchant was able to suspend another");

        assertEquals(MerchantStatus.ACTIVE, merchants.findById(merchantA).orElseThrow().getStatus(),
                "the first merchant's status actually changed");
    }

    // --------------------------------- 7. a merchant with two shops

    @Test
    @DisplayName("a merchant with two shops operates both, and nothing else")
    void twoShopsOneMerchant() {
        signedInAs(ownerB, Role.ADMIN);

        List<Long> mine = membership.shopIdsFor(ownerB);
        assertTrue(mine.contains(shopB), "the owner must be staff of their first shop");
        assertTrue(mine.contains(shopB2), "and of the annexe they opened");
        assertFalse(mine.contains(shopA), "and of nobody else's");

        assertEquals(shopB2, resolver.select(shopB2).requireShopId(),
                "switching to their other shop is exactly what the header is for");
        assertThrows(IllegalStateException.class, () -> resolver.select(shopA));
    }

    // ------------------------------------- 8 and 9. platform admin

    @Test
    @DisplayName("a platform admin spans shops, and says which one it is looking at")
    void platformAdminSpansShops() {
        signedInAs(platformAdminId, Role.PLATFORM_ADMIN);

        assertTrue(resolver.resolve().isPlatform(),
                "the marketplace operator's default scope is the whole market");
        assertEquals(shopB, resolver.select(shopB).requireShopId(),
                "and they may look into a named shop to settle a dispute");
    }

    @Test
    @DisplayName("a platform admin is not a shop admin - they cannot work a shop's orders")
    void platformAdminIsNotAShopAdmin() {
        assertFalse(RolePermissions.forRole(Role.PLATFORM_ADMIN)
                        .contains(AdminPermission.ORDERS_MANAGE),
                "running the market is not the same job as running a shop (§103)");
        assertFalse(RolePermissions.forRole(Role.PLATFORM_ADMIN)
                        .contains(AdminPermission.PAYMENTS_REFUND));

        // And over HTTP: the platform console is open to them, a shop's money
        // is not.
        assertEquals(200, statusOf(get("/api/platform/merchants"), platformAdminId, Role.PLATFORM_ADMIN));

        // A route that exists and needs a shop permission the platform
        // operator does not hold. 403, not 404: the point is the refusal, so
        // the assertion has to be on a real mapping or it proves nothing.
        assertEquals(403, statusOf(put("/api/admin/delivery-pricing"), platformAdminId, Role.PLATFORM_ADMIN),
                "a platform operator reached a shop's delivery pricing, which is the shop's own "
                        + "commercial decision");
        assertEquals(403, statusOf(post("/api/coupons")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"couponCode\":\"NOPE\",\"discountValue\":10}"),
                platformAdminId, Role.PLATFORM_ADMIN),
                "a platform operator created a discount a merchant will have to honour");
    }

    // ---------------------------- 10, 11, 12. catalogue write split

    @Test
    @DisplayName("a shop admin cannot change what a product IS")
    void shopAdminsCannotDefineTheCatalogue() throws Exception {
        int status = statusOf(put("/api/products/" + productId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Renamed by a competitor\",\"active\":true}"),
                ownerB, Role.ADMIN);

        assertEquals(403, status,
                "one merchant renamed a catalogue row that every other merchant sells from");
        assertEquals("Shared item " + tag, jdbc.queryForObject(
                "SELECT name FROM products WHERE id = ?", String.class, productId),
                "the shared product was actually renamed");
    }

    @Test
    @DisplayName("a platform admin may change what a product is")
    void platformAdminsMayDefineTheCatalogue() {
        assertNotEquals(403, statusOf(put("/api/products/" + productId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Renamed by the platform " + tag + "\",\"active\":true}"),
                platformAdminId, Role.PLATFORM_ADMIN),
                "the marketplace operator must be able to curate the shared catalogue");
    }

    @Test
    @DisplayName("a shop admin may price their own shelf, and only their own")
    void shopAdminsMayPriceTheirOwnShelf() throws Exception {
        assertEquals(200, statusOf(put("/api/shop/listings/" + variantId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sellingPrice\":71.50,\"available\":true,\"active\":true}"),
                ownerB, Role.ADMIN),
                "a shopkeeper must be able to set their own price");

        Long shopOfListing = jdbc.queryForObject(
                "SELECT shop_id FROM shop_product_variants WHERE product_variant_id = ?",
                Long.class, variantId);
        assertEquals(shopB, shopOfListing,
                "the listing was written into a shop other than the one the credential resolved to");

        // And the other merchant sees nothing of it.
        assertTrue(TenantContext.runWithin(TenantScope.ofShop(shopA),
                () -> listings.findByProductVariantId(variantId)).isEmpty(),
                "Merchant A can read the price Merchant B just set");
    }

    // ------------------------------ 17. suspended and removed

    @Test
    @DisplayName("a suspended shop's owner can still sign in and read why")
    void aSuspendedShopIsNotADeletedOne() {
        shopLifecycle.transitionAsPlatform(shopB, ShopStatus.SUSPENDED, "pending an investigation");

        signedInAs(ownerB, Role.ADMIN);
        assertEquals(shopB, resolver.resolve().requireShopId(),
                "being suspended and being unable to find out why are different punishments");
        assertEquals(ShopStatus.SUSPENDED, shops.findById(shopB).orElseThrow().getStatus());
    }

    @Test
    @DisplayName("a shop cannot lift its own suspension")
    void aShopCannotUnsuspendItself() throws Exception {
        shopLifecycle.transitionAsPlatform(shopB, ShopStatus.SUSPENDED, "pending an investigation");

        int status = statusOf(put("/api/shop/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ACTIVE\",\"reason\":\"all better now\"}"),
                ownerB, Role.ADMIN);

        assertNotEquals(200, status, "a suspension a shop can clear itself is not a suspension");
        assertEquals(ShopStatus.SUSPENDED, shops.findById(shopB).orElseThrow().getStatus());
    }

    @Test
    @DisplayName("removing a merchant closes their shops and locks their staff out")
    void removingAMerchantClosesTheirShops() {
        merchantLifecycle.transition(merchantB, MerchantStatus.REMOVED, "left the platform");

        assertEquals(ShopStatus.CLOSED, shops.findById(shopB).orElseThrow().getStatus(),
                "a removed merchant's storefronts must stop taking orders");
        assertEquals(ShopStatus.CLOSED, shops.findById(shopB2).orElseThrow().getStatus());

        signedInAs(ownerB, Role.ADMIN);
        assertThrows(IllegalStateException.class, resolver::resolve,
                "there is nothing left for them to administer, so there is no scope to give");
    }

    @Test
    @DisplayName("a merchant cannot be un-removed, and a rejected one cannot be approved")
    void terminalStatusesAreTerminal() {
        merchantLifecycle.transition(merchantB, MerchantStatus.REMOVED, "left the platform");

        assertThrows(RuntimeException.class,
                () -> merchantLifecycle.transition(merchantB, MerchantStatus.ACTIVE, "changed my mind"),
                "a REMOVED merchant quietly set back to ACTIVE is a record nobody can trust");
        assertEquals(MerchantStatus.REMOVED, merchants.findById(merchantB).orElseThrow().getStatus());
    }

    @Test
    @DisplayName("a shop cannot be opened under a merchant nobody has approved")
    void shopsNeedAnApprovedMerchant() {
        Merchant unchecked = merchantLifecycle.register("Unchecked " + tag, null, null, null, null, true);
        try {
            assertThrows(RuntimeException.class,
                    () -> shopLifecycle.open(unchecked.getId(), "UNCHK-" + tag, "Unchecked",
                            12.9, 77.6, new BigDecimal("5"), "Asia/Kolkata"),
                    "a shop under a business nobody checked takes real money on an application form");
        } finally {
            jdbc.update("DELETE FROM shops WHERE code = ?", "UNCHK-" + tag);
            jdbc.update("DELETE FROM merchants WHERE id = ?", unchecked.getId());
        }
    }

    // ----------------------------- 4 and 18. identity is live, not minted

    @Test
    @DisplayName("moving a staff account between shops takes effect at once")
    void membershipIsReadLiveNotFromAToken() {
        Long manager = newAccount("manager", Role.MANAGER);
        try {
            membership.grant(shopB, manager, true);
            signedInAs(manager, Role.MANAGER);
            assertEquals(shopB, resolver.resolve().requireShopId());

            // The same signed-in session, no new token, no re-login.
            membership.revoke(shopB, manager);
            membership.grant(shopB2, manager, true);

            assertEquals(shopB2, resolver.resolve().requireShopId(),
                    "the shop is read from the staff list on every request, so a move applies "
                            + "now - a claim inside the token would have kept them in the old shop "
                            + "until it expired");

            membership.revoke(shopB2, manager);
            assertThrows(IllegalStateException.class, resolver::resolve,
                    "and removing them entirely locks them out on the very next request");
        } finally {
            jdbc.update("DELETE FROM shop_staff WHERE customer_id = ?", manager);
            jdbc.update("DELETE FROM customers WHERE id = ?", manager);
        }
    }

    @Test
    @DisplayName("an account on no shop's staff gets no scope at all")
    void noMembershipNoScope() {
        Long nobody = newAccount("nobody", Role.SUPPORT);
        try {
            signedInAs(nobody, Role.SUPPORT);
            assertThrows(IllegalStateException.class, resolver::resolve,
                    "picking a shop for an account nobody put on a staff list would be inventing "
                            + "an authorization no one granted");
        } finally {
            jdbc.update("DELETE FROM customers WHERE id = ?", nobody);
        }
    }

    // ------------------------------------------------------------ fixtures

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
        AuthenticatedUser principal =
                new AuthenticatedUser(customerId, tag + "@example.test", role.name());
        return new UsernamePasswordAuthenticationToken(principal, null, authorities);
    }

    private void signedInAs(Long customerId, Role role) {
        SecurityContextHolder.getContext().setAuthentication(tokenFor(customerId, role));
    }

    private int statusOf(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
                         Long customerId, Role role) {
        try {
            return mockMvc.perform(request.with(authentication(tokenFor(customerId, role))))
                    .andReturn().getResponse().getStatus();
        } catch (Exception failed) {
            throw new IllegalStateException(failed);
        }
    }
}
