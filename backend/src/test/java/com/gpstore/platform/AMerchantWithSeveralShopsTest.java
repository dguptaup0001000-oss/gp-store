package com.gpstore.platform;

import com.gpstore.platform.api.ShopSelfServiceController;
import com.gpstore.repository.CustomerRepository;
import com.gpstore.entity.Customer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * §4: ONE MERCHANT, SEVERAL KIRANAS - AND NOT A FOURTH.
 *
 * <p>The data model has always allowed it (Shop.merchantId) and the way in
 * has always enforced it: TenantResolver.select accepts an X-Shop-Id naming a
 * shop the credential already permits and refuses every other, so a shop id
 * from a request can NARROW and can never grant (§13).
 *
 * <p>WHAT WAS MISSING WAS THE LIST. Nothing told the app which shops those
 * were, so a merchant with three kiranas had no way to reach the second and
 * third - and an account on two rosters with no default could not resolve a
 * shop at all, because choosing one for them would have been choosing one
 * merchant's data over another's. /api/shop/my-shops is that list, and it is
 * not an authorization: it reports grants that already exist.
 *
 * <p>The half that matters more is the one after it. A list is only safe if
 * naming something NOT on it is refused, so this asserts both directions:
 * their own second shop opens, and the shop across the road does not.
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
@DisplayName("A merchant with several shops")
class AMerchantWithSeveralShopsTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private ShopRepository shops;
    @Autowired private MerchantRepository merchants;
    @Autowired private PlatformProperties platform;
    @Autowired private ShopMembership membership;
    @Autowired private TenantResolver resolver;
    @Autowired private CustomerRepository customers;
    @Autowired private ShopSelfServiceController shopSelfService;

    private final String tag = "multi" + System.nanoTime();

    private Long ourMerchant;
    private Long theirMerchant;
    private long ourFirstShop;
    private long ourSecondShop;
    private long theirShop;
    private Long ourAccount;

    @BeforeEach
    void oneMerchantWithTwoShopsAndOneCompetitor() {
        TenantDefaults.install(PlatformMode.MULTI_SHOP_PRODUCTION,
                () -> shops.findByCode(platform.getFirstShopCode()).orElseThrow().getId());

        ourMerchant = newMerchant("Ours " + tag);
        theirMerchant = newMerchant("Theirs " + tag);

        ourFirstShop = newShop(ourMerchant, "MULTI-A-" + tag, "Our first kirana");
        ourSecondShop = newShop(ourMerchant, "MULTI-B-" + tag, "Our second kirana");
        theirShop = newShop(theirMerchant, "MULTI-C-" + tag, "Somebody else's kirana");

        Customer account = new Customer();
        account.setFullName("Shopkeeper " + tag);
        account.setEmail("multi" + tag + "@example.test");
        account.setMobileNumber("9" + tag.substring(tag.length() - 9));
        account.setPassword("not-used");
        ourAccount = customers.save(account).getId();

        // BOTH OF OURS, one of them the default. The default is what a request
        // with no header resolves to; the other is reachable only by asking.
        membership.grant(ourFirstShop, ourAccount, true);
        membership.grant(ourSecondShop, ourAccount, false);

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        new com.gpstore.security.AuthenticatedUser(
                                ourAccount, "multi" + tag + "@example.test", "ADMIN"),
                        "n/a",
                        List.of(new SimpleGrantedAuthority(
                                com.gpstore.security.AdminPermission.CATALOG_VIEW.authority()))));
    }

    @AfterEach
    void tidyUp() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
        jdbc.update("DELETE FROM shop_staff WHERE customer_id = ?", ourAccount);
        jdbc.update("DELETE FROM customers WHERE id = ?", ourAccount);
        for (long shop : List.of(ourFirstShop, ourSecondShop, theirShop)) {
            jdbc.update("DELETE FROM store_operations_settings WHERE shop_id = ?", shop);
            jdbc.update("DELETE FROM delivery_pricing_settings WHERE shop_id = ?", shop);
            jdbc.update("DELETE FROM shop_staff WHERE shop_id = ?", shop);
            jdbc.update("DELETE FROM shops WHERE id = ?", shop);
        }
        jdbc.update("DELETE FROM merchants WHERE id IN (?, ?)", ourMerchant, theirMerchant);
        TenantDefaults.install(PlatformMode.SINGLE_SHOP,
                () -> shops.findByCode(platform.getFirstShopCode()).orElseThrow().getId());
    }

    @Test
    @DisplayName("the switcher lists both of theirs and none of anybody else's")
    void mySh0psListsWhatTheCredentialAlreadyPermits() {
        ShopSelfServiceController.MyShops mine = TenantContext.runWithin(
                TenantScope.ofShop(ourFirstShop), () -> shopSelfService.myShops());

        Set<Long> listed = mine.shops().stream()
                .map(ShopSelfServiceController.ShopChoice::shopId)
                .collect(Collectors.toSet());

        assertEquals(Set.of(ourFirstShop, ourSecondShop), listed,
                "A MERCHANT WITH THREE KIRANAS COULD ONLY EVER REACH ONE OF THEM. The grants "
                        + "were there; nothing told the app about them.");
        assertFalse(listed.contains(theirShop),
                "and the list is grants that exist, not a directory of the marketplace");

        assertEquals(ourFirstShop, mine.acting(),
                "the switcher has to be able to show which one is selected without guessing");
        assertTrue(mine.shops().stream()
                        .filter(s -> s.shopId().equals(ourFirstShop))
                        .allMatch(ShopSelfServiceController.ShopChoice::acting));
    }

    @Test
    @DisplayName("they can act for their second shop, by asking for it")
    void selectingTheirOwnSecondShopIsAllowed() {
        TenantScope scope = resolver.select(ourSecondShop);
        assertEquals(ourSecondShop, scope.shopId(),
                "the shop they are on the roster of, which is the whole point of a switcher");
    }

    @Test
    @DisplayName("they cannot act for the shop across the road, however they ask")
    void selectingSomebodyElsesShopIsRefused() {
        // §13: a shop id from a request may NARROW to something the credential
        // already permits and may NEVER grant. This is the never.
        assertThrows(RuntimeException.class, () -> resolver.select(theirShop),
                "A SHOP ID IN A HEADER IS NOT AN AUTHORIZATION. A merchant whose switcher "
                        + "quietly showed them the shop across the road's orders would be worse "
                        + "than an error, so it is an error.");
    }

    @Test
    @DisplayName("a shop the platform has closed is listed but not offered as workable")
    void aClosedShopIsShownAsNotOperable() {
        jdbc.update("UPDATE shops SET status = 'CLOSED' WHERE id = ?", ourSecondShop);

        ShopSelfServiceController.MyShops mine = TenantContext.runWithin(
                TenantScope.ofShop(ourFirstShop), () -> shopSelfService.myShops());

        ShopSelfServiceController.ShopChoice closed = mine.shops().stream()
                .filter(s -> s.shopId().equals(ourSecondShop))
                .findFirst().orElseThrow();

        assertFalse(closed.operable(),
                "OFFERING IT IN A SWITCHER WOULD BE OFFERING A SCREEN THAT ERRORS ON ARRIVAL. "
                        + "It is still listed - the shopkeeper should see their own shop and "
                        + "why it is greyed out - but it is marked for what it is.");
        assertTrue(mine.shops().stream()
                        .filter(s -> s.shopId().equals(ourFirstShop))
                        .allMatch(ShopSelfServiceController.ShopChoice::operable));
    }

    // -------------------------------------------------------------- fixtures

    private Long newMerchant(String name) {
        Merchant m = new Merchant();
        m.setLegalName(name);
        m.setDisplayName(name);
        m.setStatus(MerchantStatus.ACTIVE);
        m.setIsDemo(Boolean.TRUE);
        m.setActive(Boolean.TRUE);
        return merchants.save(m).getId();
    }

    private long newShop(Long merchantId, String code, String name) {
        Shop shop = new Shop();
        shop.setMerchantId(merchantId);
        shop.setCode(code);
        shop.setDisplayName(name);
        shop.setStatus(ShopStatus.ACTIVE);
        shop.setLatitude(27.16);
        shop.setLongitude(83.94);
        shop.setMaxDeliveryRadiusKm(new BigDecimal("5"));
        shop.setIsDemo(Boolean.TRUE);
        shop.setActive(Boolean.TRUE);
        return shops.save(shop).getId();
    }
}
