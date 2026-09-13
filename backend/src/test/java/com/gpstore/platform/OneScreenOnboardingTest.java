package com.gpstore.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.test.web.servlet.MockMvc;

import com.gpstore.entity.Role;
import com.gpstore.security.AuthenticatedUser;
import com.gpstore.security.RolePermissions;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Onboarding a merchant is one call, or it is nothing.
 *
 * WHAT THIS REPLACES. Opening a merchant by hand was a login, a business, two
 * status changes and a shop - five round trips with four places to stop
 * halfway. Every one of those stops leaves something real behind: a login
 * nobody can use, a business stuck in APPLICATION, a merchant with no shop.
 * The platform owner kept finding exactly those half-states.
 *
 * So the test that matters most here is not the happy path - it is that a
 * refusal partway through leaves no wreckage.
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
@DisplayName("One screen onboards a merchant, or leaves nothing behind")
class OneScreenOnboardingTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private PlatformOnboardingService onboarding;
    @Autowired private ShopLifecycleService shopLifecycle;
    @Autowired private MerchantRepository merchants;
    @Autowired private ShopRepository shops;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper objectMapper;

    private final List<Long> madeMerchants = new ArrayList<>();
    private final List<Long> madeShops = new ArrayList<>();
    private final List<Long> madeCustomers = new ArrayList<>();

    private String tag() {
        return "onboard" + System.nanoTime();
    }

    private static String uniquePhone() {
        return "9" + String.format("%09d", Math.abs(System.nanoTime() % 1_000_000_000L));
    }

    private PlatformOnboardingService.OnboardedMerchant onboardOne(String business, String email) {
        // A PHONE PER FIXTURE. customers.mobile_number is UNIQUE, so a
        // shared literal here fails the second onboarding on a constraint
        // rather than on anything this test is about.
        PlatformOnboardingService.OnboardedMerchant result = onboarding.onboard(
                business, "Owner Of " + business, email, uniquePhone(),
                null, 26.7606, 83.3732, new BigDecimal("5.0"), null, false);
        remember(result);
        return result;
    }

    private void remember(PlatformOnboardingService.OnboardedMerchant result) {
        madeMerchants.add(result.merchantId());
        madeShops.add(result.shopId());
        madeCustomers.add(result.ownerCustomerId());
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
            jdbc.update("DELETE FROM audit_logs WHERE entity_id = ? AND entity_type = 'Merchant'",
                    merchantId);
            jdbc.update("DELETE FROM merchants WHERE id = ?", merchantId);
        }
        for (Long customerId : madeCustomers) {
            jdbc.update("DELETE FROM refresh_tokens WHERE customer_id = ?", customerId);
            jdbc.update("DELETE FROM audit_logs WHERE entity_id = ? AND entity_type = 'Customer'",
                    customerId);
            jdbc.update("DELETE FROM customers WHERE id = ?", customerId);
        }
        madeShops.clear();
        madeMerchants.clear();
        madeCustomers.clear();
    }

    @Test
    @DisplayName("the admin app sends X-Shop-Id, and opening a shop must survive it")
    void openingAShopWhileTheAppIsPointedAtAnotherOne() throws Exception {
        String tag = tag();
        // WHAT THE PHONE ACTUALLY SENDS. ApiClient attaches X-Shop-Id to
        // every non-auth request once a shop is selected, and in the admin
        // app one always is - the account's home shop. So the platform
        // console's "open a shop" arrives with the header naming a DIFFERENT,
        // already-existing shop, which narrows a platform admin's scope to
        // that shop for the whole request.
        //
        // Nothing in the tests reached this: they all call the service
        // directly, in platform scope, with no header at all.
        var first = onboardOne("Header Sender " + tag, tag + "@example.test");

        mockMvc.perform(post("/api/platform/shops")
                        .with(authentication(platformOwner()))
                        .header("X-Shop-Id", String.valueOf(first.shopId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "merchantId", first.merchantId(),
                                "code", "hdr-" + tag,
                                "displayName", "Branch Two",
                                "latitude", 26.7606,
                                "longitude", 83.3732,
                                "maxDeliveryRadiusKm", 15,
                                "timeZone", "Asia/Kolkata"))))
                .andExpect(status().isOk());

        Long opened = jdbc.queryForObject(
                "SELECT id FROM shops WHERE code = ?", Long.class, "hdr-" + tag);
        assertNotNull(opened, "the shop must exist after a 200");
        madeShops.add(opened);

        // THE SETTINGS MUST BELONG TO THE NEW SHOP, not to the one the header
        // named. A row stamped with the header's shop collides with that
        // shop's existing row on uk_store_operations_settings_shop, and the
        // refusal reaches the phone as the generic "that already exists".
        Integer ops = jdbc.queryForObject(
                "SELECT count(*) FROM store_operations_settings WHERE shop_id = ?",
                Integer.class, opened);
        assertEquals(1, ops, "the new shop needs its own operating settings");
        Integer pricing = jdbc.queryForObject(
                "SELECT count(*) FROM delivery_pricing_settings WHERE shop_id = ?",
                Integer.class, opened);
        assertEquals(1, pricing, "the new shop needs its own delivery pricing");
    }

    @Test
    @DisplayName("a merchant whose owner already has a home shop can open a second one")
    void aSecondShopUnderAnOwnerWhoIsAlreadySomewhere() {
        String tag = tag();
        // THE PRODUCTION SHAPE, and the reason this test exists. The first
        // shop makes its owner's HOME shop - open() grants them a default
        // shop_staff row so a new storefront is not one nobody can sign in
        // to. Opening the SECOND shop then runs grant() again for an account
        // that already holds a default, and uk_shop_staff_one_default is a
        // unique index over (customer_id) WHERE is_default AND active.
        //
        // Every fixture that opens ONE shop misses this entirely: its owner
        // had no home shop when grant ran. On the live marketplace, the only
        // merchant that exists has an owner who has had one since the day the
        // shop opened, so the first thing the platform owner ever tried was
        // exactly this.
        var first = onboardOne("Already Trading " + tag, tag + "@example.test");

        Shop second = shopLifecycle.open(first.merchantId(), "second-" + tag,
                "Second Branch", 26.7606, 83.3732, new BigDecimal("15.0"), "Asia/Kolkata");
        madeShops.add(second.getId());

        assertEquals(first.merchantId(), second.getMerchantId());

        // THE OWNER KEEPS THE HOME THEY WERE WORKING IN. A second branch is
        // not a relocation, and nobody asked to be moved.
        Long home = jdbc.queryForObject(
                "SELECT shop_id FROM shop_staff WHERE customer_id = ? AND active IS TRUE "
                        + "AND is_default IS TRUE", Long.class, first.ownerCustomerId());
        assertEquals(first.shopId(), home,
                "opening a second shop must not move the owner out of their first");

        // And they are on the new shop's staff, or the branch has nobody.
        Integer onSecond = jdbc.queryForObject(
                "SELECT count(*) FROM shop_staff WHERE shop_id = ? AND customer_id = ? "
                        + "AND active IS TRUE", Integer.class, second.getId(), first.ownerCustomerId());
        assertEquals(1, onSecond,
                "the owner must be able to work in the branch that was just opened for them");
    }

    @Test
    @DisplayName("one call produces a merchant, a shop and a password to hand over")
    void oneCallDoesTheWholeThing() {
        String tag = tag();
        var result = onboardOne("Sharma Kirana " + tag, tag + "@example.test");

        Merchant merchant = merchants.findById(result.merchantId()).orElseThrow();
        Shop shop = shops.findById(result.shopId()).orElseThrow();

        // APPROVED, not APPLICATION: the ceremony the owner used to tap
        // through is done, and the shop exists under it.
        assertEquals(MerchantStatus.APPROVED, merchant.getStatus());
        assertEquals(merchant.getId(), shop.getMerchantId());
        assertNotNull(result.oneTimePassword());
        assertTrue(result.oneTimePassword().length() >= 12);

        // The owner can actually reach their shop: open() grants the staff
        // row, and without it the merchant signs in to nothing.
        Integer staffRows = jdbc.queryForObject(
                "SELECT count(*) FROM shop_staff WHERE shop_id = ? AND customer_id = ? "
                        + "AND is_default IS TRUE", Integer.class,
                result.shopId(), result.ownerCustomerId());
        assertEquals(1, staffRows);
    }

    @Test
    @DisplayName("it stops short of trading, because the shelves are empty")
    void itDoesNotSwitchOnAnEmptyShop() {
        String tag = tag();
        var result = onboardOne("Empty Shelves " + tag, tag + "@example.test");

        Merchant merchant = merchants.findById(result.merchantId()).orElseThrow();
        Shop shop = shops.findById(result.shopId()).orElseThrow();

        // A findable shop with nothing to sell is worse than one that is not
        // findable yet. ShopReadiness counts listings and stock as blocking
        // for the same reason.
        assertEquals(MerchantStatus.APPROVED, merchant.getStatus());
        assertEquals(ShopStatus.DRAFT, shop.getStatus());
    }

    @Test
    @DisplayName("the shop is opened where it is, and says how far it delivers")
    void theShopCanActuallyBeFound() {
        String tag = tag();
        var result = onboardOne("Located Shop " + tag, tag + "@example.test");

        Shop shop = shops.findById(result.shopId()).orElseThrow();

        // THE TRAP THIS CLOSES. openShop itself allows both to be null, and a
        // shop without them looks finished and is offered to nobody - the
        // marketplace matches by distance.
        assertNotNull(shop.getLatitude());
        assertNotNull(shop.getLongitude());
        assertTrue(shop.getMaxDeliveryRadiusKm().signum() > 0);
    }

    @Test
    @DisplayName("a refusal at the LAST step leaves no login, no business, no shop")
    void nothingSurvivesAFailure() {
        String tag = tag();

        // THE FAILURE HAS TO BE LATE, and getting that wrong is how this test
        // first passed for the wrong reason. A duplicate email refuses inside
        // openAccount - step one, before anything exists - so it proves
        // nothing about a rollback. Removing @Transactional did not fail it.
        //
        // A shop code that is already taken refuses in shops.open(), which
        // runs AFTER the staff login is created, after the merchant is
        // registered, and after both status transitions. Exactly the state a
        // client doing this in five round trips would be left holding.
        var first = onboardOne("Code Holder " + tag, tag + "a@example.test");
        String takenCode = first.shopCode();

        long merchantsBefore = merchants.count();
        long shopsBefore = shops.count();
        String secondEmail = tag + "b@example.test";

        assertThrows(RuntimeException.class, () -> onboarding.onboard(
                "Loser " + tag, "Someone Else", secondEmail, uniquePhone(),
                takenCode, 26.7606, 83.3732, new BigDecimal("5.0"), null, false));

        // NOTHING NEW. Not a spare login, not a business stuck in APPLICATION
        // or APPROVED, not a shop. This is the whole reason it is one
        // transaction rather than five calls from a phone.
        assertEquals(0, countCustomers(secondEmail),
                "the staff account created before the refusal must be rolled back");
        assertEquals(merchantsBefore, merchants.count(),
                "the merchant registered before the refusal must be rolled back");
        assertEquals(shopsBefore, shops.count());
        assertEquals(MerchantStatus.APPROVED,
                merchants.findById(first.merchantId()).orElseThrow().getStatus(),
                "and the one that succeeded is untouched");
    }

    /** /api/platform/** is gated on PERM_PLATFORM_ADMIN, which only the owner holds. */
    private UsernamePasswordAuthenticationToken platformOwner() {
        List<GrantedAuthority> authorities = new ArrayList<>();
        for (String authority : RolePermissions.authorityNames(Role.SUPER_ADMIN)) {
            authorities.add(new SimpleGrantedAuthority(authority));
        }
        return new UsernamePasswordAuthenticationToken(
                new AuthenticatedUser(1L, "owner@example.test", Role.SUPER_ADMIN.name()),
                null, authorities);
    }

    private long countCustomers(String email) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM customers WHERE lower(email) = lower(?)",
                Integer.class, email);
        return n == null ? 0 : n;
    }

    @Test
    @DisplayName("a shop with no location is refused, not quietly opened")
    void locationIsNotOptionalHere() {
        String tag = tag();
        var refusal = assertThrows(RuntimeException.class, () -> onboarding.onboard(
                "No Pin " + tag, "Owner", tag + "@example.test", null,
                null, null, null, new BigDecimal("5.0"), null, false));

        assertTrue(refusal.getMessage().toLowerCase().contains("where is the shop"),
                "the refusal must say what is missing: " + refusal.getMessage());
        assertEquals(0, countCustomers(tag + "@example.test"),
                "a refused onboarding must not leave a login behind");
    }

    @Test
    @DisplayName("a shop that will not say how far it delivers is refused too")
    void radiusIsNotOptionalHere() {
        String tag = tag();
        assertThrows(RuntimeException.class, () -> onboarding.onboard(
                "No Radius " + tag, "Owner", tag + "@example.test", null,
                null, 26.7606, 83.3732, null, null, false));

        assertEquals(0, countCustomers(tag + "@example.test"));
    }

    @Test
    @DisplayName("a phone somebody else already uses is a sentence, not a 500")
    void aDuplicatePhoneIsExplained() {
        String tag = tag();
        String phone = uniquePhone();
        var first = onboarding.onboard("Phone Holder " + tag, "Owner", tag + "a@example.test",
                phone, null, 26.7606, 83.3732, new BigDecimal("5.0"), null, false);
        remember(first);

        // FOUND BY THIS TEST'S OWN FIXTURE, which is how it earned its place.
        // customers.mobile_number is UNIQUE, and openAccount checked only the
        // email - so a duplicate number arrived as a
        // DataIntegrityViolationException with a constraint name in it.
        //
        // It is the likely collision: a shopkeeper being onboarded may
        // already shop on GP-STORE under that number.
        var refusal = assertThrows(RuntimeException.class, () -> onboarding.onboard(
                "Second " + tag, "Other Owner", tag + "b@example.test", phone,
                null, 26.7606, 83.3732, new BigDecimal("5.0"), null, false));

        assertTrue(refusal.getMessage().toLowerCase().contains("phone number"),
                "the refusal must name the phone: " + refusal.getMessage());
        assertEquals(0, countCustomers(tag + "b@example.test"),
                "and it must leave nothing behind");
    }

    @Test
    @DisplayName("the shop code is derived, and made unique without being asked for")
    void codesAreDerivedNotDemanded() {
        String tag = tag();
        var first = onboardOne("Gupt Saree " + tag, tag + "a@example.test");
        var second = onboardOne("Gupt Saree " + tag, tag + "b@example.test");

        // A shopkeeper has no opinion about a shop code and no way to know
        // which are taken. Two businesses with the same name still get two
        // codes rather than a conflict the owner has to resolve.
        assertTrue(first.shopCode().startsWith("gupt-saree"), first.shopCode());
        assertTrue(second.shopCode().startsWith("gupt-saree"), second.shopCode());
        assertTrue(!first.shopCode().equals(second.shopCode()),
                "two shops cannot share a code");
    }

    @Test
    @DisplayName("a name of pure punctuation still gets a usable code")
    void slugNeverComesOutEmpty() {
        assertEquals("shop", PlatformOnboardingService.slug("!!! ###"));
        assertEquals("gupt-saree", PlatformOnboardingService.slug("  GUPT   SAREE  "));
        assertTrue(PlatformOnboardingService.slug("x".repeat(80)).length() <= 36,
                "shops.code is varchar(40) and the de-duplicating suffix needs room");
    }

    @Test
    @DisplayName("over HTTP it answers with the password and the shop it opened")
    void overHttp() throws Exception {
        String tag = tag();
        String body = mockMvc.perform(post("/api/platform/onboard")
                        .with(authentication(platformOwner()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"businessName":"HTTP Kirana %s",
                                 "ownerName":"Owner %s",
                                 "ownerEmail":"%s@example.test",
                                 "latitude":26.7606,
                                 "longitude":83.3732,
                                 "maxDeliveryRadiusKm":4.5}
                                """.formatted(tag, tag, tag)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.oneTimePassword").isNotEmpty())
                .andExpect(jsonPath("$.shopId").isNumber())
                .andReturn().getResponse().getContentAsString();

        JsonNode json = objectMapper.readTree(body);
        madeMerchants.add(json.get("merchantId").asLong());
        madeShops.add(json.get("shopId").asLong());
        madeCustomers.add(json.get("ownerCustomerId").asLong());

        assertTrue(json.get("shopCode").asText().startsWith("http-kirana"));
    }
}
