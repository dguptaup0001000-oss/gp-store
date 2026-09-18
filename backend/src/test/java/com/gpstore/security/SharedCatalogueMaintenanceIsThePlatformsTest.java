package com.gpstore.security;

import com.gpstore.entity.Role;
import com.gpstore.platform.MerchantLifecycleService;
import com.gpstore.platform.MerchantStatus;
import com.gpstore.platform.PlatformProperties;
import com.gpstore.platform.ShopLifecycleService;
import com.gpstore.platform.ShopRepository;
import com.gpstore.platform.ShopStatus;
import com.gpstore.platform.TenantDefaults;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;

/**
 * On a marketplace, the shared catalogue's maintenance belongs to the platform.
 *
 * <p>WHAT THE SWEEP FOUND. {@code /api/admin/catalog/**} was gated on
 * SYSTEM_ADMIN, and every shop owner holds SYSTEM_ADMIN: {@code Role.ADMIN} is
 * EVERY_SHOP_PERMISSION, which is written as a subtraction of three
 * permissions, and SYSTEM_ADMIN is not one of them. So a merchant could
 *
 * <ul>
 *   <li>seed the platform's catalogue with a fixed product list,</li>
 *   <li>start up to a thousand outbound image fetches per call,</li>
 *   <li>migrate every catalogue image to R2, and</li>
 *   <li>with {@code ?confirm=true}, delete every product flagged
 *       {@code is_test_data} across the whole marketplace - including rows
 *       other shops have listed, priced and stocked.</li>
 * </ul>
 *
 * <p>The confirm flag guards against a stray tap, not against the wrong
 * person. CATALOG_DEFINE is the permission that means "writes the shared
 * catalogue", and it is one of the three deliberately withheld from merchants.
 *
 * <p>NOT A REMOVAL OF ANYBODY'S TOOLS. Under a single shop,
 * CatalogDefinitionAuthorization hands catalogue definition to CATALOG_MANAGE,
 * so the kirana owner keeps every one of these routes - that is what
 * CatalogAdminAuthorizationTest, which declares SINGLE_SHOP, still asserts.
 * And the bulk IMPORTER stays the merchant's on any deployment: it lists what
 * it imports onto the importing shop's own shelf, and the admin app ships a
 * screen for it.
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
@DisplayName("The shared catalogue's maintenance is the platform's, not a merchant's")
class SharedCatalogueMaintenanceIsThePlatformsTest {

    private static final String SEED = "/api/admin/catalog/seed";
    private static final String AUDIT = "/api/admin/catalog/audit";
    private static final String IMAGES = "/api/admin/catalog/images/backfill";
    private static final String R2 = "/api/admin/catalog/images/migrate-to-r2";
    private static final String TEST_DATA = "/api/admin/catalog/test-data";
    private static final String IMPORT_TEMPLATE = "/api/admin/catalog/import/template";

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ShopRepository shops;
    @Autowired private PlatformProperties platform;
    @Autowired private MerchantLifecycleService merchantLifecycle;
    @Autowired private ShopLifecycleService shopLifecycle;

    private final String tag = "cat" + System.nanoTime();

    private Long merchantId;
    private long shopId;
    private Long owner;
    private Long platformOwner;

    @BeforeEach
    void aMerchantWithAShop() {
        TenantDefaults.install(platform.getMode(),
                () -> shops.findByCode(platform.getFirstShopCode()).orElseThrow().getId());

        merchantId = merchantLifecycle.register(
                "Catalogue Merchant " + tag, "Catalogue Shop",
                null, null, null, true).getId();
        merchantLifecycle.transition(merchantId, MerchantStatus.PENDING_REVIEW, "submitted");
        merchantLifecycle.transition(merchantId, MerchantStatus.APPROVED, "checked");
        merchantLifecycle.transition(merchantId, MerchantStatus.ACTIVE, "trading");

        shopId = shopLifecycle.open(merchantId, "CAT-" + tag, "Catalogue Shop",
                12.9716, 77.5946, new java.math.BigDecimal("5"), "Asia/Kolkata").getId();
        shopLifecycle.transitionAsPlatform(shopId, ShopStatus.ACTIVE, "ready to trade");

        owner = newAccount("Catalogue Owner " + tag, tag + "-owner@example.test", "ADMIN");
        jdbc.update("INSERT INTO shop_staff (shop_id, customer_id, is_default, active) "
                + "VALUES (?, ?, true, true)", shopId, owner);
        platformOwner = newAccount("Catalogue Platform " + tag,
                tag + "-platform@example.test", "SUPER_ADMIN");
    }

    @AfterEach
    void tidyUp() {
        jdbc.update("DELETE FROM shop_staff WHERE shop_id = ?", shopId);
        jdbc.update("DELETE FROM shop_business_hours WHERE shop_id = ?", shopId);
        jdbc.update("DELETE FROM delivery_pricing_settings WHERE shop_id = ?", shopId);
        jdbc.update("DELETE FROM store_operations_settings WHERE shop_id = ?", shopId);
        jdbc.update("DELETE FROM shops WHERE id = ?", shopId);
        jdbc.update("DELETE FROM merchants WHERE id = ?", merchantId);
        jdbc.update("DELETE FROM customers WHERE id IN (?, ?)", owner, platformOwner);
        TenantDefaults.install(platform.getMode(),
                () -> shops.findByCode(platform.getFirstShopCode()).orElseThrow().getId());
    }

    @Nested
    @DisplayName("what a merchant may not do to the shared catalogue")
    class TheMerchant {

        @Test
        @DisplayName("may not seed it")
        void cannotSeed() throws Exception {
            assertEquals(403, as(post(SEED), owner, Role.ADMIN).getResponse().getStatus(),
                    "a merchant seeded the platform's catalogue");
        }

        @Test
        @DisplayName("may not start a thousand outbound image fetches")
        void cannotBackfillImages() throws Exception {
            assertEquals(403, as(post(IMAGES), owner, Role.ADMIN).getResponse().getStatus(),
                    "a merchant started the platform's image backfill");
        }

        @Test
        @DisplayName("may not migrate every catalogue image to R2")
        void cannotMigrateImages() throws Exception {
            assertEquals(403,
                    as(post(R2 + "?confirm=true"), owner, Role.ADMIN).getResponse().getStatus(),
                    "a merchant started the platform's image migration");
        }

        @Test
        @DisplayName("may not delete every test product on the marketplace")
        void cannotDeleteTestData() throws Exception {
            // ASSERTED WITHOUT ?confirm=true ON PURPOSE. A 403 here proves the
            // authorization refuses before the endpoint's own safety catch is
            // even reached; sending confirm=true to prove it would mean asking
            // a shared database to delete rows to make a point.
            assertEquals(403, as(delete(TEST_DATA), owner, Role.ADMIN).getResponse().getStatus(),
                    "a merchant reached the marketplace-wide test-product deletion");
        }

        @Test
        @DisplayName("may not read the platform's catalogue audit")
        void cannotReadAudit() throws Exception {
            assertEquals(403, as(get(AUDIT), owner, Role.ADMIN).getResponse().getStatus(),
                    "a merchant read the platform's catalogue counts");
        }

        @Test
        @DisplayName("keeps the bulk importer, which is their own tool")
        void keepsTheImporter() throws Exception {
            // THE FEATURE MUST SURVIVE THE FIX. The admin app ships an Import
            // Catalogue screen; taking it away from merchants would be a worse
            // bug than the one this class is about.
            int status = as(get(IMPORT_TEMPLATE), owner, Role.ADMIN).getResponse().getStatus();
            assertNotEquals(403, status,
                    "the merchant's own bulk importer was closed along with the "
                            + "platform's catalogue maintenance");
            assertEquals(200, status, "the importer's template stopped being served");
        }
    }

    @Nested
    @DisplayName("what the platform keeps")
    class ThePlatform {

        @Test
        @DisplayName("reads the catalogue audit")
        void readsAudit() throws Exception {
            assertEquals(200,
                    as(get(AUDIT), platformOwner, Role.SUPER_ADMIN).getResponse().getStatus(),
                    "the platform owner lost the catalogue audit");
        }

        @Test
        @DisplayName("reaches the deletion, and is still stopped by its confirm flag")
        void reachesDeletionBehindItsGuard() throws Exception {
            // 400, not 403: authorised, and refused by the endpoint's own
            // safety catch because confirm was not sent. Nothing is deleted.
            assertEquals(400,
                    as(delete(TEST_DATA), platformOwner, Role.SUPER_ADMIN)
                            .getResponse().getStatus(),
                    "the platform owner was refused the deletion outright, or the "
                            + "confirm guard stopped working");
        }
    }

    // ------------------------------------------------------------------

    private Long newAccount(String name, String email, String role) {
        jdbc.update("INSERT INTO customers (full_name, email, mobile_number, password, "
                        + "role, active) VALUES (?, ?, ?, 'not-a-real-hash', ?, true)",
                name, email, "9" + (100000000 + (int) (Math.random() * 899999999)), role);
        return jdbc.queryForObject("SELECT id FROM customers WHERE email = ?", Long.class, email);
    }

    private MvcResult as(MockHttpServletRequestBuilder request, Long accountId, Role role)
            throws Exception {
        List<GrantedAuthority> authorities = new ArrayList<>();
        for (String authority : RolePermissions.authorityNames(role)) {
            authorities.add(new SimpleGrantedAuthority(authority));
        }
        request.with(authentication(new UsernamePasswordAuthenticationToken(
                new AuthenticatedUser(accountId, tag + "@example.test", role.name()),
                null, authorities)));
        return mockMvc.perform(request).andReturn();
    }
}
