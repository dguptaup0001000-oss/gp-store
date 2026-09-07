package com.gpstore.platform;

import com.gpstore.platform.api.ShopSelfServiceController;
import com.gpstore.platform.shopinfo.ShopPolicyKind;
import com.gpstore.platform.shopinfo.ShopPolicyRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * §10: A BADGE THE PLATFORM GRANTS, AND ONE THAT CANNOT BE GRANTED AT ALL.
 *
 * <p>THE TWO HALVES ARE DIFFERENT KINDS OF RULE, and both need saying.
 *
 * <p>VERIFIED and BUSINESS_VERIFIED are granted after somebody at GP-STORE
 * looks at documents. The rule is that the shop being verified is not that
 * somebody: a merchant who can set their own verification level has been
 * verified by nobody, and the badge is then worth less than no badge at all,
 * because a customer reads it as a check that was made.
 *
 * <p>TRUSTED is not granted. §10 says it must be EARNED and must not be
 * purchasable, and the way this codebase makes that true is by there being
 * nothing to purchase: no column, no field, no setter, no route. It is
 * computed from the shop's own trading record whenever anybody asks, so a
 * shop that stops delivering stops being trusted the same week - with nobody
 * having to remember to take anything away.
 *
 * <p>Which is why half this test is written against the SHAPE of the code
 * rather than against a response. "There is no way to set this" is not
 * something a request can demonstrate; the absence of the route is the
 * assertion.
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
@DisplayName("A verification badge is granted; a trusted one is earned")
class ShopVerificationIsEarnedTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private ShopRepository shops;
    @Autowired private MerchantRepository merchants;
    @Autowired private PlatformProperties platform;
    @Autowired private ShopLifecycleService lifecycle;
    @Autowired private ShopReliability reliability;
    @Autowired private ShopPolicyRepository policies;
    @Autowired private ShopSelfServiceController shopSelfService;

    private final String tag = "vfy" + System.nanoTime();

    private long shopA;
    private long shopB;
    private Long merchantId;

    @BeforeEach
    void twoShops() {
        TenantDefaults.install(PlatformMode.MULTI_SHOP_PRODUCTION,
                () -> shops.findByCode(platform.getFirstShopCode()).orElseThrow().getId());
        shopA = shops.findByCode(platform.getFirstShopCode()).orElseThrow().getId();

        Merchant m = new Merchant();
        m.setLegalName("Verification fixture " + tag);
        m.setDisplayName("Verification fixture");
        m.setStatus(MerchantStatus.ACTIVE);
        m.setIsDemo(Boolean.TRUE);
        m.setActive(Boolean.TRUE);
        merchantId = merchants.save(m).getId();

        Shop b = new Shop();
        b.setMerchantId(merchantId);
        b.setCode("VFY-" + tag);
        b.setDisplayName("Unverified kirana");
        b.setStatus(ShopStatus.ACTIVE);
        b.setLatitude(27.16);
        b.setLongitude(83.94);
        b.setMaxDeliveryRadiusKm(new BigDecimal("5"));
        b.setIsDemo(Boolean.TRUE);
        b.setActive(Boolean.TRUE);
        shopB = shops.save(b).getId();
    }

    @AfterEach
    void tidyUp() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
        TenantContext.clear();
        // Shop #1 is the live shop: it must not keep a badge this test gave it.
        jdbc.update("UPDATE shops SET verification_level = 'NONE', verified_at = NULL, "
                + "verified_by = NULL, verification_note = NULL WHERE id = ?", shopA);
        for (long shop : List.of(shopA, shopB)) {
            jdbc.update("DELETE FROM shop_policies WHERE shop_id = ?", shop);
        }
        jdbc.update("DELETE FROM store_operations_settings WHERE shop_id = ?", shopB);
        jdbc.update("DELETE FROM delivery_pricing_settings WHERE shop_id = ?", shopB);
        jdbc.update("DELETE FROM shops WHERE id = ?", shopB);
        jdbc.update("DELETE FROM merchants WHERE id = ?", merchantId);
        TenantDefaults.install(platform.getMode(),
                () -> shops.findByCode(platform.getFirstShopCode()).orElseThrow().getId());
    }

    // ------------------------------------------------------------- granted

    @Test
    @DisplayName("every shop starts unverified, including the one already trading")
    void nobodyIsVerifiedByDefault() {
        assertEquals(ShopVerificationLevel.NONE,
                shops.findById(shopA).orElseThrow().getVerificationLevel(),
                "BACKFILLING A BADGE ONTO SHOP #1 WOULD BE THE PLATFORM VOUCHING FOR A SHOP IT "
                        + "HAS NOT LOOKED AT. A badge nobody checked is worth less than none.");
        assertEquals(ShopVerificationLevel.NONE,
                shops.findById(shopB).orElseThrow().getVerificationLevel());
        assertNull(shops.findById(shopA).orElseThrow().getVerificationLevel().badge(),
                "and an unverified shop shows no badge rather than a bad one");
    }

    @Test
    @DisplayName("the platform grants it, and withdrawing it clears the date too")
    void theplatformGrantsAndWithdraws() {
        Shop verified = lifecycle.verify(shopB, ShopVerificationLevel.BUSINESS_VERIFIED,
                "GST and FSSAI checked " + tag);
        assertEquals(ShopVerificationLevel.BUSINESS_VERIFIED, verified.getVerificationLevel());
        assertNotNull(verified.getVerifiedAt());
        assertEquals("Business verified", verified.getVerificationLevel().badge());
        assertTrue(verified.getVerificationLevel().isAtLeast(ShopVerificationLevel.VERIFIED),
                "business verification includes everything plain verification means");

        Shop withdrawn = lifecycle.verify(shopB, ShopVerificationLevel.NONE, "licence lapsed");
        assertEquals(ShopVerificationLevel.NONE, withdrawn.getVerificationLevel());
        assertNull(withdrawn.getVerifiedAt(),
                "\"VERIFIED ON THE 3RD\" BESIDE A SHOP THAT IS NO LONGER VERIFIED still reads "
                        + "as an endorsement, so the date goes with the badge");
    }

    @Test
    @DisplayName("verifying one shop does not verify the shop next door")
    void verificationIsPerShop() {
        lifecycle.verify(shopB, ShopVerificationLevel.VERIFIED, "checked " + tag);

        assertEquals(ShopVerificationLevel.VERIFIED,
                shops.findById(shopB).orElseThrow().getVerificationLevel());
        assertEquals(ShopVerificationLevel.NONE,
                shops.findById(shopA).orElseThrow().getVerificationLevel(),
                "a badge that spread would be worth nothing on either shop");
    }

    @Test
    @DisplayName("a merchant has no way to verify themselves")
    void aMerchantCannotVerifyItself() {
        // THE ASSERTION IS THE ABSENCE. A shopkeeper who can set their own
        // verification level has been verified by nobody, so the check is that
        // the merchant's own update carries no such field at all - not that a
        // request with one is rejected, because there is no request to make.
        List<String> fields = Arrays.stream(
                        ShopSelfServiceController.ProfileUpdate.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)
                .toList();

        assertFalse(fields.stream().anyMatch(f -> f.toLowerCase().contains("verif")),
                "ShopSelfServiceController.ProfileUpdate carries a verification field: " + fields);
        assertFalse(fields.stream().anyMatch(f -> f.toLowerCase().contains("trust")),
                "and it must certainly not carry a trusted one");

        assertTrue(fields.contains("gstin") && fields.contains("businessName"),
                "the shopkeeper DOES state their business details - they say who they are, and "
                        + "the platform is what checks it");

        boolean merchantRouteWrites = Arrays.stream(ShopSelfServiceController.class.getMethods())
                .map(Method::getName)
                .anyMatch(name -> name.toLowerCase().contains("verif")
                        || name.toLowerCase().contains("trust"));
        assertFalse(merchantRouteWrites,
                "the merchant controller has a route touching verification or trust");
    }

    // -------------------------------------------------------------- earned

    @Test
    @DisplayName("trusted is computed, and a new shop is simply not there yet")
    void trustedIsEarnedNotSet() {
        ShopReliability.Record record = TenantContext.runWithin(TenantScope.ofShop(shopB),
                () -> reliability.forCurrentShop());

        assertFalse(record.trusted(), "a shop that opened a moment ago has earned nothing yet");
        assertNotNull(record.whyNot(),
                "A BADGE A SHOPKEEPER CANNOT FIND OUT HOW TO EARN IS A BADGE THAT LOOKS BOUGHT. "
                        + "The answer has to say what is missing.");
        assertTrue(record.whyNot().contains("orders"),
                "and what is missing here is a trading record: " + record.whyNot());
        assertEquals(0, record.orderCount());

        // AND IT IS NOT "NO", IT IS "NOT YET". A thin record is not a bad one;
        // treating it as one would make the badge impossible rather than hard.
        assertEquals(0, record.cancelled());
    }

    @Test
    @DisplayName("there is nowhere to write trusted, at any price")
    void trustedHasNoStorage() {
        Integer columns = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns "
                        + "WHERE table_schema = current_schema() AND table_name = 'shops' "
                        + "AND column_name IN ('trusted', 'is_trusted', 'trusted_level')",
                Integer.class);
        assertEquals(0, columns,
                "§10 SAYS TRUSTED MUST NOT BE PURCHASABLE. A column that exists is a value that "
                        + "can be set, and a value that can be set is one that can be sold. The "
                        + "guarantee is that there is nothing there.");

        boolean shopHasTrustedField = Arrays.stream(Shop.class.getDeclaredFields())
                .map(java.lang.reflect.Field::getName)
                .anyMatch(name -> name.toLowerCase().contains("trust"));
        assertFalse(shopHasTrustedField, "and no field on the entity either");
    }

    // ------------------------------------------------------------ promises

    /**
     * A shopkeeper with the permission the route requires.
     *
     * <p>The two policy cases below go through the CONTROLLER rather than the
     * repository, because the rule they check - an empty body removes rather
     * than stores - lives there. That means passing its permission check,
     * which is itself worth having: a signed-out caller reaching setPolicy
     * fails, and it failed exactly that way the first time this ran.
     */
    private void asShopkeeper() {
        org.springframework.security.core.context.SecurityContextHolder.getContext()
                .setAuthentication(new org.springframework.security.authentication
                        .UsernamePasswordAuthenticationToken(
                        new com.gpstore.security.AuthenticatedUser(
                                1L, "shopkeeper-" + tag + "@example.test", "ADMIN"),
                        "n/a",
                        List.of(new org.springframework.security.core.authority
                                .SimpleGrantedAuthority(
                                com.gpstore.security.AdminPermission.CATALOG_MANAGE.authority()),
                                new org.springframework.security.core.authority
                                        .SimpleGrantedAuthority(
                                        com.gpstore.security.AdminPermission.CATALOG_VIEW.authority()))));
    }

    @Test
    @DisplayName("a shop's policies are its own")
    void policiesArePerShop() {
        asShopkeeper();
        TenantContext.runWithin(TenantScope.ofShop(shopB), () ->
                shopSelfService.setPolicy(ShopPolicyKind.RETURNS.name(),
                        new ShopSelfServiceController.PolicyUpdate(
                                "Bring it back within a day " + tag)));

        List<?> onB = TenantContext.runWithin(TenantScope.ofShop(shopB),
                () -> policies.findAllByOrderByKindAsc());
        List<?> onA = TenantContext.runWithin(TenantScope.ofShop(shopA),
                () -> policies.findAllByOrderByKindAsc());

        assertEquals(1, onB.size());
        assertTrue(onA.isEmpty(),
                "ONE SHOPKEEPER'S RETURNS POLICY IS NOT THE SHOP NEXT DOOR'S PROMISE. It is the "
                        + "sentence they will be held to.");
    }

    @Test
    @DisplayName("clearing a policy removes it rather than storing an empty promise")
    void anEmptyPolicyIsRemoved() {
        asShopkeeper();
        TenantContext.runWithin(TenantScope.ofShop(shopB), () ->
                shopSelfService.setPolicy(ShopPolicyKind.DELIVERY.name(),
                        new ShopSelfServiceController.PolicyUpdate("Within two hours " + tag)));
        TenantContext.runWithin(TenantScope.ofShop(shopB), () ->
                shopSelfService.setPolicy(ShopPolicyKind.DELIVERY.name(),
                        new ShopSelfServiceController.PolicyUpdate("   ")));

        assertTrue(TenantContext.runWithin(TenantScope.ofShop(shopB),
                        () -> policies.findAllByOrderByKindAsc()).isEmpty(),
                "a heading with nothing under it reads as a policy the customer cannot find, "
                        + "which is worse than a shop that has not written one");
    }
}
