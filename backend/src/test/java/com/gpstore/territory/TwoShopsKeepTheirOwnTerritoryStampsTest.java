package com.gpstore.territory;

import com.gpstore.entity.Address;
import com.gpstore.entity.DeliverySubzone;
import com.gpstore.platform.MerchantLifecycleService;
import com.gpstore.platform.MerchantStatus;
import com.gpstore.platform.PlatformProperties;
import com.gpstore.platform.ShopLifecycleService;
import com.gpstore.platform.ShopRepository;
import com.gpstore.platform.ShopStatus;
import com.gpstore.platform.TenantContext;
import com.gpstore.platform.TenantDefaults;
import com.gpstore.platform.TenantScope;
import com.gpstore.repository.AddressRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One house, two shops, two answers - and neither shop can touch the other's.
 *
 * <p>WHAT THIS REPLACES. A territory stamp used to be two columns on the
 * customer's address: {@code subzone_id} and {@code subzone_locked}. One house
 * has one row there, and a marketplace has as many maps over it as there are
 * shops delivering to it, so the shops overwrote each other:
 *
 * <ul>
 *   <li>whoever saved the address, re-resolved their map or pinned the house
 *       last owned the stamp, and the other shop's answer was simply gone;</li>
 *   <li>{@code subzone_locked} was read by EVERY shop's re-resolve, so one
 *       merchant pinning one house froze it for all of them;</li>
 *   <li>a shop that read a stamp belonging to somebody else got nothing back -
 *       the subzone is shop-owned, so the tenant filter hid it - and quietly
 *       resolved live instead, which is the drift stamping exists to prevent.
 *       Its round moved under it whenever a rival edited a boundary.</li>
 * </ul>
 *
 * <p>The stamp is now a row in {@code address_territory_stamps}, one per shop,
 * shop-owned like the territory it names. Every assertion below is a sentence
 * that could not have been true before.
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
@DisplayName("Two shops over one house keep their own territory stamps")
class TwoShopsKeepTheirOwnTerritoryStampsTest {

    /** Both shops draw the same ground. A test whose maps did not overlap would pass either way. */
    private static final String SQUARE =
            "[[28.60,77.20],[28.60,77.24],[28.62,77.24],[28.62,77.20],[28.60,77.20]]";
    /** A smaller square inside it, for the boundary edit that must move only one shop. */
    private static final String HALF =
            "[[28.60,77.20],[28.60,77.22],[28.62,77.22],[28.62,77.20],[28.60,77.20]]";
    private static final double LAT = 28.610;
    private static final double LNG = 77.230;

    @Autowired private AddressTerritory territory;
    @Autowired private AddressTerritoryStampRepository stamps;
    @Autowired private AddressRepository addresses;
    @Autowired private com.gpstore.service.AddressService addressService;
    @Autowired private TerritoryResolver resolver;
    @Autowired private TerritoryAdminService territoryAdmin;
    @Autowired private com.gpstore.repository.DeliverySubzoneRepository subzones;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ShopRepository shops;
    @Autowired private PlatformProperties platform;
    @Autowired private MerchantLifecycleService merchantLifecycle;
    @Autowired private ShopLifecycleService shopLifecycle;

    private final String tag = "tsk" + System.nanoTime();
    private final String code = "TSK" + Long.toString(System.nanoTime(), 36);

    private Long merchantA;
    private Long merchantB;
    private long shopA;
    private long shopB;
    private long subzoneA;
    private long subzoneB;
    private Long customerId;
    private Long addressId;

    @BeforeEach
    void twoShopsDrawTheSameGround() {
        TenantDefaults.install(platform.getMode(),
                () -> shops.findByCode(platform.getFirstShopCode()).orElseThrow().getId());
        wipe();

        merchantA = newMerchant("a");
        merchantB = newMerchant("b");
        shopA = newShop(merchantA, "TKA-" + tag);
        shopB = newShop(merchantB, "TKB-" + tag);
        subzoneA = newTerritory(shopA, code + "A", SQUARE);
        subzoneB = newTerritory(shopB, code + "B", SQUARE);

        jdbc.update("INSERT INTO customers (full_name, email, mobile_number, password, role, active) "
                        + "VALUES (?, ?, ?, 'not-a-real-hash', 'CUSTOMER', true)",
                "Stamp Buyer " + tag, tag + "@example.test",
                "9" + (100000000 + (int) (Math.random() * 899999999)));
        customerId = jdbc.queryForObject(
                "SELECT id FROM customers WHERE email = ?", Long.class, tag + "@example.test");
        jdbc.update("INSERT INTO addresses (customer_id, house_no, area, city, state, pincode, "
                        + "latitude, longitude, default_address) "
                        + "VALUES (?, '1', 'Stamp Lane', 'Testville', 'TS', '110001', ?, ?, true)",
                customerId, LAT, LNG);
        addressId = jdbc.queryForObject(
                "SELECT id FROM addresses WHERE customer_id = ?", Long.class, customerId);

        // THEY HAVE BOUGHT FROM BOTH SHOPS, which is the situation this whole
        // file is about - and it is also what entitles either shopkeeper to
        // act on the house at all (see ShopCustomers).
        newOrder(shopA, "TKA-ORD-" + tag);
        newOrder(shopB, "TKB-ORD-" + tag);

        resolver.invalidate();
    }

    @AfterEach
    void tidyUp() {
        wipe();
        TenantContext.clear();
        resolver.invalidate();
        TenantDefaults.install(platform.getMode(),
                () -> shops.findByCode(platform.getFirstShopCode()).orElseThrow().getId());
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("each shop records its own answer, and neither sees the other's")
    void eachShopStampsItsOwn() {
        Address address = address();

        Long forA = asShop(shopA, () -> territory.territoryForDispatch(address)
                .map(DeliverySubzone::getId).orElse(null));
        Long forB = asShop(shopB, () -> territory.territoryForDispatch(address)
                .map(DeliverySubzone::getId).orElse(null));

        assertEquals(subzoneA, forA, "shop A dispatched into its own territory");
        assertEquals(subzoneB, forB,
                "shop B got " + forB + " - it must resolve its OWN map. With one shared column "
                        + "the second shop to dispatch simply took the first one's answer");

        assertEquals(subzoneA, stampedFor(shopA), "shop A's stamp was overwritten");
        assertEquals(subzoneB, stampedFor(shopB), "shop B's stamp was overwritten");

        // And the filter, not a rule in a service, is what keeps them apart.
        assertTrue(asShop(shopA, () -> stamps.findAll().stream()
                        .allMatch(s -> shopA == s.getShopId())),
                "a shop read another shop's stamp rows");
    }

    @Test
    @DisplayName("a boundary edit moves only the shop that made it")
    void aBoundaryEditMovesOnlyItsOwnShop() {
        Address address = address();
        asShop(shopA, () -> territory.territoryForDispatch(address));
        asShop(shopB, () -> territory.territoryForDispatch(address));

        // Shop A shrinks its territory so this house falls outside it, and
        // re-resolves - the deliberate act that is allowed to move customers.
        jdbc.update("UPDATE delivery_subzones SET boundary = ? WHERE id = ?", HALF, subzoneA);
        resolver.invalidate();
        asShop(shopA, () -> territoryAdmin.reresolveAllAddresses(200));

        assertNull(stampedFor(shopA), "shop A's own re-resolve did not move its own stamp");
        assertEquals(subzoneB, stampedFor(shopB),
                "shop A's boundary edit moved shop B's customer out of shop B's territory. "
                        + "That is the shared column, exactly: one merchant redrawing their map "
                        + "used to re-stamp a house for everybody");
    }

    @Test
    @DisplayName("one merchant's pin does not freeze the house for anybody else")
    void aPinBindsOnlyTheShopThatMadeIt() {
        Address address = address();
        asShop(shopA, () -> territory.territoryForDispatch(address));
        asShop(shopB, () -> territory.territoryForDispatch(address));

        // A person at shop A places this house by hand - the colony whose only
        // gate opens into the next territory.
        asShop(shopA, () -> territoryAdmin.pinAddress(addressId, subzoneA));

        assertTrue(asShop(shopA, () -> territory.stampFor(addressId)
                        .map(AddressTerritoryStamp::isLocked).orElse(false)),
                "shop A's own pin did not stick");
        assertFalse(asShop(shopB, () -> territory.stampFor(addressId)
                        .map(AddressTerritoryStamp::isLocked).orElse(false)),
                "shop A's pin locked the house for shop B too. subzone_locked was one column "
                        + "read by every shop's re-resolve, so one merchant's judgement about "
                        + "one house silently became every merchant's");

        // And shop B's own re-resolve still moves shop B, because nothing about
        // shop B's answer was pinned.
        jdbc.update("UPDATE delivery_subzones SET boundary = ? WHERE id = ?", HALF, subzoneB);
        resolver.invalidate();
        asShop(shopB, () -> territoryAdmin.reresolveAllAddresses(200));

        assertNull(stampedFor(shopB), "shop B was frozen by a pin that was not its own");
        assertEquals(subzoneA, stampedFor(shopA), "shop B's re-resolve moved shop A's pin");
    }

    @Test
    @DisplayName("a merchant cannot pin a house into another shop's territory")
    void aPinCannotReachAnotherShopsMap() {
        Address address = address();
        asShop(shopA, () -> territory.territoryForDispatch(address));

        // subzoneB is shop B's row; requireSubzone reads it through the
        // filtered repository, so from shop A it does not exist.
        assertTrue(asShop(shopA, () -> {
            try {
                territoryAdmin.pinAddress(addressId, subzoneB);
                return false;
            } catch (RuntimeException refused) {
                return true;
            }
        }), "one merchant pinned a customer into another merchant's territory");

        assertEquals(subzoneA, stampedFor(shopA), "the refused pin still changed a stamp");
        assertNull(stampedFor(shopB), "the refused pin wrote a stamp for the other shop");
    }

    @Test
    @DisplayName("moving the house makes every shop's answer stale, including a pinned one")
    void movingTheHouseInvalidatesEveryStamp() {
        Address address = address();
        asShop(shopA, () -> territory.territoryForDispatch(address));
        asShop(shopB, () -> territory.territoryForDispatch(address));
        // Shop A has even placed this house by hand.
        asShop(shopA, () -> territoryAdmin.pinAddress(addressId, subzoneA));

        assertTrue(stampIsAboutWhereTheHouseIs(shopA));
        assertTrue(stampIsAboutWhereTheHouseIs(shopB));

        // The customer corrects a pin that was two streets out. Nobody tells
        // the shops; each of them finds out from its own row, because a stamp
        // records the point it was resolved from.
        jdbc.update("UPDATE addresses SET latitude = ?, longitude = ? WHERE id = ?",
                28.605, 77.205, addressId);

        assertFalse(stampIsAboutWhereTheHouseIs(shopA),
                "shop A's answer is about the old location and must not be used - not even a "
                        + "pinned one, because the person placed the house where it is not");
        assertFalse(stampIsAboutWhereTheHouseIs(shopB),
                "shop B's answer is about the old location and must not be used");

        // And each shop refreshes its own the next time it dispatches there.
        Address moved = address();
        asShop(shopB, () -> territory.territoryForDispatch(moved));
        assertTrue(stampIsAboutWhereTheHouseIs(shopB), "shop B did not re-resolve");
        assertFalse(stampIsAboutWhereTheHouseIs(shopA),
                "shop B's dispatch refreshed shop A's stamp - the shops are not supposed to "
                        + "write each other's rows at all");
    }

    @Test
    @DisplayName("an edit through the app refreshes the acting shop and stales the rest")
    void editingThroughTheAppRefreshesOnlyTheActingShop() {
        Address address = address();
        asShop(shopA, () -> territory.territoryForDispatch(address));
        asShop(shopB, () -> territory.territoryForDispatch(address));

        asShop(shopA, () -> {
            Address edit = addresses.findById(addressId).orElseThrow();
            edit.setLatitude(28.605);
            edit.setLongitude(77.205);
            addressService.updateAddress(addressId, edit);
        });

        assertTrue(stampIsAboutWhereTheHouseIs(shopA),
                "the shop that handled the edit should have re-resolved for the new point");
        assertFalse(stampIsAboutWhereTheHouseIs(shopB),
                "shop B's answer is still about the old location and must not be used");
    }

    @Test
    @DisplayName("the platform pins on a named shop's behalf, and the territory says whose")
    void thePlatformPinsIntoTheTerritorysOwnShop() {
        Address address = address();
        asShop(shopB, () -> territory.territoryForDispatch(address));

        // A platform administrator answering a support call places this house
        // into SHOP A's territory. They never say "shop A" - the territory is
        // shop A's row, so the stamp can only be shop A's, which is also what
        // stops a platform pin landing in nobody's map.
        TenantContext.runWithin(TenantScope.platform(),
                () -> territoryAdmin.pinAddress(addressId, subzoneA));

        assertEquals(subzoneA, stampedFor(shopA), "the platform's pin did not reach shop A");
        assertTrue(asShop(shopA, () -> territory.stampFor(addressId)
                        .map(AddressTerritoryStamp::isLocked).orElse(false)),
                "the platform's pin did not lock shop A's stamp");
        assertEquals(subzoneB, stampedFor(shopB),
                "the platform's pin into shop A's map moved shop B's answer as well");
        assertFalse(asShop(shopB, () -> territory.stampFor(addressId)
                        .map(AddressTerritoryStamp::isLocked).orElse(false)),
                "the platform's pin into shop A's map locked shop B's stamp too");
    }

    @Test
    @DisplayName("the platform console has no map of its own, and is told so")
    void thePlatformIsAskedWhichShopItMeans() {
        Address address = address();
        asShop(shopA, () -> territory.territoryForDispatch(address));

        RuntimeException refused = org.junit.jupiter.api.Assertions.assertThrows(
                RuntimeException.class,
                () -> TenantContext.runWithin(TenantScope.platform(),
                        () -> territoryAdmin.reresolveAllAddresses(200)),
                "a platform-wide re-resolve has no map to run - it must ask which shop, not "
                        + "quietly walk everybody's houses");
        assertTrue(String.valueOf(refused.getMessage()).toLowerCase().contains("shop"),
                "the refusal must say what to do: " + refused.getMessage());

        assertEquals(subzoneA, stampedFor(shopA), "the refused run still changed a stamp");
    }

    // ------------------------------------------------------------------

    private Address address() {
        return TenantContext.runWithin(TenantScope.platform(),
                () -> addresses.findById(addressId).orElseThrow());
    }

    /** Whether THIS shop's stamp describes the point the house is at now. */
    private boolean stampIsAboutWhereTheHouseIs(long shopId) {
        Address now = address();
        return asShop(shopId, () -> territory.stampFor(addressId)
                .map(stamp -> stamp.describes(now.getLatitude(), now.getLongitude()))
                .orElse(false));
    }

    /** What THIS shop has stamped, read as that shop. */
    private Long stampedFor(long shopId) {
        return asShop(shopId, () -> territory.stampFor(addressId)
                .map(stamp -> stamp.getSubzone() == null ? null : stamp.getSubzone().getId())
                .orElse(null));
    }

    private <T> T asShop(long shopId, java.util.function.Supplier<T> work) {
        return TenantContext.runWithin(TenantScope.ofShop(shopId), work::get);
    }

    private void asShop(long shopId, Runnable work) {
        TenantContext.runWithin(TenantScope.ofShop(shopId), () -> {
            work.run();
            return null;
        });
    }

    private void newOrder(long shopId, String orderNumber) {
        jdbc.update("INSERT INTO orders (shop_id, customer_id, order_number, order_status, "
                        + "total_amount, order_date) VALUES (?, ?, ?, 'DELIVERED', 100, now())",
                shopId, customerId, orderNumber);
    }

    private Long newMerchant(String kind) {
        Long id = merchantLifecycle.register(
                "Stamp Merchant " + kind + " " + tag, "Stamp Shop",
                null, null, null, true).getId();
        merchantLifecycle.transition(id, MerchantStatus.PENDING_REVIEW, "submitted");
        merchantLifecycle.transition(id, MerchantStatus.APPROVED, "checked");
        merchantLifecycle.transition(id, MerchantStatus.ACTIVE, "trading");
        return id;
    }

    private long newShop(Long merchant, String shopCode) {
        long id = shopLifecycle.open(merchant, shopCode, "Stamp Shop " + shopCode,
                LAT, LNG, new java.math.BigDecimal("5"), "Asia/Kolkata").getId();
        shopLifecycle.transitionAsPlatform(id, ShopStatus.ACTIVE, "ready to trade");
        return id;
    }

    private long newTerritory(long shopId, String territoryCode, String boundary) {
        jdbc.update("INSERT INTO delivery_zones (code, name, active, shop_id) "
                + "VALUES (?, ?, true, ?)", "Z" + territoryCode, "StampZone " + tag, shopId);
        Long zone = jdbc.queryForObject("SELECT id FROM delivery_zones WHERE code = ?",
                Long.class, "Z" + territoryCode);
        jdbc.update("INSERT INTO delivery_subzones (code, name, active, shop_id, zone_id, "
                        + "boundary, max_concurrent_orders) VALUES (?, ?, true, ?, ?, ?, 10)",
                territoryCode, "StampTerritory " + tag, shopId, zone, boundary);
        return jdbc.queryForObject("SELECT id FROM delivery_subzones WHERE code = ?",
                Long.class, territoryCode);
    }

    private void wipe() {
        jdbc.update("DELETE FROM address_territory_stamps WHERE subzone_id IN "
                + "(SELECT id FROM delivery_subzones WHERE name LIKE 'StampTerritory %')");
        if (customerId != null) {
            jdbc.update("DELETE FROM orders WHERE customer_id = ?", customerId);
        }
        if (addressId != null) {
            jdbc.update("DELETE FROM address_territory_stamps WHERE address_id = ?", addressId);
            jdbc.update("DELETE FROM addresses WHERE id = ?", addressId);
            addressId = null;
        }
        jdbc.update("DELETE FROM delivery_subzones WHERE name LIKE 'StampTerritory %'");
        jdbc.update("DELETE FROM delivery_zones WHERE name LIKE 'StampZone %'");
        if (customerId != null) {
            jdbc.update("DELETE FROM orders WHERE customer_id = ?", customerId);
            jdbc.update("DELETE FROM customers WHERE id = ?", customerId);
            customerId = null;
        }
        for (Long shop : new Long[]{shopA == 0 ? null : shopA, shopB == 0 ? null : shopB}) {
            if (shop == null) {
                continue;
            }
            jdbc.update("DELETE FROM shop_staff WHERE shop_id = ?", shop);
            jdbc.update("DELETE FROM shop_business_hours WHERE shop_id = ?", shop);
            jdbc.update("DELETE FROM delivery_pricing_settings WHERE shop_id = ?", shop);
            jdbc.update("DELETE FROM store_operations_settings WHERE shop_id = ?", shop);
            jdbc.update("DELETE FROM shops WHERE id = ?", shop);
        }
        shopA = 0;
        shopB = 0;
        for (Long merchant : new Long[]{merchantA, merchantB}) {
            if (merchant != null) {
                jdbc.update("DELETE FROM merchants WHERE id = ?", merchant);
            }
        }
        merchantA = null;
        merchantB = null;
    }
}
