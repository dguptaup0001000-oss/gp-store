package com.gpstore.platform;

import com.gpstore.entity.Address;
import com.gpstore.entity.Customer;
import com.gpstore.entity.Role;
import com.gpstore.repository.AddressRepository;
import com.gpstore.repository.CustomerRepository;
import com.gpstore.security.AuthenticatedUser;
import com.gpstore.security.RolePermissions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A customer finds a shop without being anybody's staff.
 *
 * THE GAP SLICE 3 LEFT, closed. Shop scope was resolved from a staff
 * membership, which is right for a shopkeeper and impossible for a customer -
 * applied to the people the marketplace exists for, it meant nobody could shop
 * on it at all. A customer's shop is the nearest one that will deliver to
 * them, or the storefront they explicitly opened.
 *
 * WHAT IS STILL NOT AN AUTHORIZATION. The scope a customer gets is a SHOP
 * scope, so every shop-owned query stays filtered to that one shop. Standing
 * in front of a window is not being handed the till, and the negative tests
 * below are what say so.
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
@DisplayName("A customer resolves a shop from where they live, not from a staff list")
class CustomerShopSelectionTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private TenantResolver resolver;
    @Autowired private ShopDiscovery discovery;
    @Autowired private ShopMembership membership;
    @Autowired private ShopRepository shops;
    @Autowired private MerchantRepository merchants;
    @Autowired private PlatformProperties platform;
    @Autowired private CustomerRepository customers;
    @Autowired private AddressRepository addresses;

    private final String tag = "csel" + System.nanoTime();

    private long nearShop;
    private long farShop;
    private long pausedShop;
    private Long merchantId;
    private Long customerId;

    // The customer's doorstep, and three shops at increasing distance.
    private static final double LAT = 12.9700;
    private static final double LNG = 77.5900;

    @BeforeEach
    void threeShopsAndACustomer() {
        installDefaultsFor(PlatformMode.MULTI_SHOP_PRODUCTION);

        Merchant m = new Merchant();
        m.setLegalName("Discovery fixture " + tag);
        m.setDisplayName("Discovery");
        m.setStatus(MerchantStatus.ACTIVE);
        m.setIsDemo(Boolean.TRUE);
        m.setActive(Boolean.TRUE);
        merchantId = merchants.save(m).getId();

        // ~0.5 km away, radius 3 km: serves this address.
        nearShop = newShop("NEAR-" + tag, LAT + 0.0045, LNG, new BigDecimal("3"), ShopStatus.ACTIVE);
        // ~5.5 km away, radius 8 km: also serves it, but further.
        farShop = newShop("FAR-" + tag, LAT + 0.0500, LNG, new BigDecimal("8"), ShopStatus.ACTIVE);
        // Close enough, but not on the marketplace.
        pausedShop = newShop("SUSP-" + tag, LAT + 0.0045, LNG, new BigDecimal("3"), ShopStatus.SUSPENDED);

        Customer customer = new Customer();
        customer.setFullName("Shopper " + tag);
        customer.setEmail(tag + "@example.test");
        customer.setMobileNumber("9" + String.valueOf(System.nanoTime()).substring(0, 9));
        customer.setPassword("not-a-real-hash");
        customer.setEnabled(true);
        customer.setActive(true);
        customer.setRole(Role.CUSTOMER);
        customer = customers.save(customer);
        customerId = customer.getId();

        Address address = new Address();
        address.setCustomer(customer);
        address.setFullName(customer.getFullName());
        address.setMobileNumber(customer.getMobileNumber());
        address.setHouseNo("1");
        address.setArea("Discovery Area");
        address.setCity("Bengaluru");
        address.setState("Karnataka");
        address.setPincode("560001");
        address.setCountry("India");
        address.setLatitude(LAT);
        address.setLongitude(LNG);
        address.setDefaultAddress(true);
        addresses.save(address);
    }

    @AfterEach
    void tidyUp() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
        jdbc.update("DELETE FROM shop_staff WHERE customer_id = ?", customerId);
        jdbc.update("DELETE FROM addresses WHERE customer_id = ?", customerId);
        jdbc.update("DELETE FROM customers WHERE id = ?", customerId);
        jdbc.update("DELETE FROM store_operations_settings WHERE shop_id in (?, ?, ?)",
                nearShop, farShop, pausedShop);
        jdbc.update("DELETE FROM delivery_pricing_settings WHERE shop_id in (?, ?, ?)",
                nearShop, farShop, pausedShop);
        jdbc.update("DELETE FROM shops WHERE id in (?, ?, ?)", nearShop, farShop, pausedShop);
        jdbc.update("DELETE FROM merchants WHERE id = ?", merchantId);
        installDefaultsFor(PlatformMode.SINGLE_SHOP);
    }

    // ------------------------------------------------------ 1, 2, 3: finding one

    @Test
    @DisplayName("a customer with no staff membership still gets a shop")
    void customersNeedNoStaffMembership() {
        assertTrue(membership.shopIdsFor(customerId).isEmpty(),
                "the fixture customer must not be staff of anything, or this proves nothing");

        signedInAsCustomer();

        TenantScope scope = resolver.resolve();
        assertTrue(scope.isSingleShop(), "a customer gets a shop, not the whole marketplace");
        assertEquals(nearShop, scope.requireShopId(),
                "and it is the NEAREST shop that will deliver to them - which is what 'local' "
                        + "has to mean, rather than whichever shop registered first");
    }

    @Test
    @DisplayName("shops are offered nearest first, each against its own radius")
    void nearestFirstWithinEachShopsOwnRadius() {
        List<ShopDiscovery.NearbyShop> serving = discovery.shopsServing(LAT, LNG);
        List<Long> ids = serving.stream().map(s -> s.shop().getId()).toList();

        assertEquals(List.of(nearShop, farShop), ids,
                "both shops reach this address - one at 3 km and one at 8 km, each by its own "
                        + "declared radius - and the closer one comes first");
        assertFalse(ids.contains(pausedShop),
                "a suspended storefront is not on the marketplace to be shopped at, however close "
                        + "it is");
        assertTrue(serving.get(0).distanceKm() < serving.get(1).distanceKm());
    }

    @Test
    @DisplayName("a shop whose own radius stops short is not offered, however near the next one is")
    void aShopsOwnRadiusIsWhatDecides() {
        // Far enough that the 3 km shop will not go, but inside the 8 km one.
        double distantLat = LAT + 0.0400;

        List<Long> ids = discovery.shopsServing(distantLat, LNG).stream()
                .map(s -> s.shop().getId()).toList();

        assertFalse(ids.contains(nearShop),
                "the radius belongs to the shop: a kirana that delivers 3 km must not be offered "
                        + "to somebody 4 km away because a different shop would go that far");
        assertTrue(ids.contains(farShop));
    }

    @Test
    @DisplayName("an address with no pin matches no shop rather than every shop")
    void noCoordinatesMeansNoShop() {
        assertTrue(discovery.shopsServing(null, null).isEmpty(),
                "failing closed is the same rule the single shop always applied - an address that "
                        + "cannot be shown to be in range is not in range");
    }

    // -------------------------------------------------- 4: browsing several

    @Test
    @DisplayName("a customer may open any shop on the marketplace, one at a time")
    void aCustomerMayBrowseMoreThanOneShop() {
        signedInAsCustomer();

        assertEquals(nearShop, resolver.select(nearShop).requireShopId());
        assertEquals(farShop, resolver.select(farShop).requireShopId(),
                "browsing a second storefront in the same session is the whole point of a "
                        + "marketplace");
    }

    @Test
    @DisplayName("a customer cannot open a shop the marketplace does not show")
    void aCustomerCannotOpenAShopThatIsNotOnTheMarketplace() {
        signedInAsCustomer();

        assertThrows(RuntimeException.class, () -> resolver.select(pausedShop),
                "a suspended shop is not a shop a customer may stand in front of - and being "
                        + "refused is what stops a suspension being cosmetic");
        assertThrows(RuntimeException.class, () -> resolver.select(999_999_999L));
    }

    @Test
    @DisplayName("a customer's scope is still only one shop's worth")
    void browsingIsNotBeingHandedTheTill() {
        signedInAsCustomer();

        TenantScope scope = resolver.select(farShop);

        assertFalse(scope.isPlatform(),
                "a customer must never be given a scope that spans shops - that is the platform "
                        + "operator's, and it is the difference between reading a price list and "
                        + "reading the marketplace's books");
        assertEquals(farShop, scope.requireShopId());
    }

    // ---------------------------------------- staff are NOT customers here

    @Test
    @DisplayName("a staff account cannot use the customer path to reach another merchant's shop")
    void staffAreRestrictedToTheirOwnShopsEvenThoughShopsAreBrowsable() {
        // THE REGRESSION THIS PINS. Letting any customer select any browsable
        // shop is correct - and applied without care it hands the same door to
        // a shopkeeper, who is also, technically, a person who could browse.
        // A staff account is restricted to the shops it is staff of, full stop.
        Long staffAccount = newStaffAccount();
        try {
            membership.grant(nearShop, staffAccount, true);
            signedInAs(staffAccount, Role.ADMIN);

            assertEquals(nearShop, resolver.select(nearShop).requireShopId(),
                    "their own shop, as always");
            assertThrows(RuntimeException.class, () -> resolver.select(farShop),
                    "a shopkeeper selecting a competitor's storefront is the cross-merchant move "
                            + "Slice 3 exists to prevent, and 'customers may browse' must not "
                            + "become a way round it");
        } finally {
            jdbc.update("DELETE FROM shop_staff WHERE customer_id = ?", staffAccount);
            jdbc.update("DELETE FROM customers WHERE id = ?", staffAccount);
        }
    }

    // ------------------------------------------------------------ fixtures

    private void installDefaultsFor(PlatformMode mode) {
        Long shopOne = shops.findByCode(platform.getFirstShopCode()).orElseThrow().getId();
        TenantDefaults.install(mode, () -> shopOne);
    }

    private long newShop(String code, double lat, double lng, BigDecimal radiusKm, ShopStatus status) {
        Shop shop = new Shop();
        shop.setMerchantId(merchantId);
        shop.setCode(code);
        shop.setDisplayName(code);
        shop.setStatus(status);
        shop.setLatitude(lat);
        shop.setLongitude(lng);
        shop.setMaxDeliveryRadiusKm(radiusKm);
        shop.setTimeZone("Asia/Kolkata");
        shop.setIsDemo(Boolean.TRUE);
        shop.setActive(Boolean.TRUE);
        return shops.save(shop).getId();
    }

    private Long newStaffAccount() {
        String email = tag + "-staff@example.test";
        jdbc.update("""
                INSERT INTO customers (full_name, email, mobile_number, password, role, active)
                VALUES (?, ?, ?, 'not-a-real-hash', 'ADMIN', true)
                """, "Staff " + tag, email,
                "8" + String.valueOf(System.nanoTime()).substring(0, 9));
        return jdbc.queryForObject("SELECT id FROM customers WHERE email = ?", Long.class, email);
    }

    private void signedInAsCustomer() {
        signedInAs(customerId, Role.CUSTOMER);
    }

    private void signedInAs(Long accountId, Role role) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        for (String authority : RolePermissions.authorityNames(role)) {
            authorities.add(new SimpleGrantedAuthority(authority));
        }
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        new AuthenticatedUser(accountId, tag + "@example.test", role.name()),
                        null, authorities));
    }
}
