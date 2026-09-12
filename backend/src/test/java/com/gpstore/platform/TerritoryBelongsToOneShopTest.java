package com.gpstore.platform;

import com.gpstore.entity.DeliveryPartner;
import com.gpstore.entity.DeliverySubzone;
import com.gpstore.entity.DeliveryZone;
import com.gpstore.repository.DeliverySubzoneRepository;
import com.gpstore.repository.DeliveryZoneRepository;
import com.gpstore.territory.TerritoryAdminService;
import com.gpstore.territory.TerritoryResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Decision W4, enforced: a territory belongs to one shop, and so does its rider.
 *
 * THE CHAIN THIS SLICE EXISTS TO CLOSE is shop-scoped territory → shop-scoped
 * rider → that shop's order. Every link had a way of leaking before it, and
 * they are different kinds of leak, so they are tested separately:
 *
 *   THE MAP. delivery_zones and delivery_subzones had no shop at all. Two
 *   kiranas 400 metres apart would have shared one map, and whichever
 *   shopkeeper drew it would have been deciding where a competitor's
 *   boundaries lay.
 *
 *   THE CACHE. TerritoryResolver held ONE map in memory for the whole
 *   process. The first shop to resolve an address filled it, and every other
 *   shop was then answered from it with no query run - the same silent shape
 *   the catalogue caches had before Slice 4, except that this one does not
 *   merely show wrong data, it picks which rider goes to a competitor's
 *   customer.
 *
 *   THE PAIRING. A subzone can name a rider, and both rows can be perfectly
 *   readable while pairing them is exactly what W4 forbids. A row filter
 *   cannot see that; it takes a rule, and the rule has to be stated.
 *
 * AND ONE OUTAGE, which is the failure mode isolation work produces when it
 * is done carelessly: a customer's address is a customer's row and spans every
 * shop they buy from, but it carries ONE subzone_id. Making subzones
 * shop-owned meant the address list itself could load another shop's row and
 * be refused. Tested here because "the shop cannot see the other's data" and
 * "the customer can still see their own addresses" are the same change.
 */
@SpringBootTest(properties = {
        "outbox.initial-delay-ms=3600000",
        "outbox.drain-interval-ms=3600000",
        "payment.expiry-initial-delay-ms=3600000",
        "idempotency.cleanup-initial-delay-ms=3600000",
        "otp.cleanup-initial-delay-ms=3600000",
        "delivery.late-flag-initial-delay-ms=3600000"
})
@DisplayName("A territory belongs to the shop that drew it, and to its riders")
class TerritoryBelongsToOneShopTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private ShopRepository shops;
    @Autowired private MerchantRepository merchants;
    @Autowired private PlatformProperties platform;
    @Autowired private DeliveryZoneRepository zones;
    @Autowired private DeliverySubzoneRepository subzones;
    @Autowired private TerritoryResolver resolver;
    @Autowired private TerritoryAdminService territoryAdmin;
    @Autowired private com.gpstore.repository.DeliveryPartnerRepository partners;
    @Autowired private com.gpstore.repository.AddressRepository addresses;

    private final String tag = "terr" + System.nanoTime();

    private long shopA;
    private long shopB;
    private Long merchantB;
    private Long customerId;
    private Long addressId;

    private long zoneAId;
    private long zoneBId;
    private long subzoneAId;
    private long subzoneBId;
    private long riderAId;
    private long riderBId;

    /**
     * The SAME square of ground, drawn by both shops.
     *
     * Overlapping deliberately: two kiranas on one street serve the same
     * houses, and a test whose shops cover different places would pass
     * whether or not the maps were kept apart.
     */
    private static final String OVERLAPPING_SQUARE =
            "[[28.60,77.20],[28.60,77.22],[28.62,77.22],[28.62,77.20],[28.60,77.20]]";
    private static final double INSIDE_LAT = 28.61;
    private static final double INSIDE_LNG = 77.21;

    @BeforeEach
    void twoShopsDrawingTheSameGround() {
        shopA = shops.findByCode(platform.getFirstShopCode()).orElseThrow().getId();

        Merchant second = new Merchant();
        second.setLegalName("Territory fixture merchant " + tag);
        second.setDisplayName("Territory B");
        second.setStatus(MerchantStatus.ACTIVE);
        second.setIsDemo(Boolean.TRUE);
        second.setActive(Boolean.TRUE);
        merchantB = merchants.save(second).getId();

        Shop b = new Shop();
        b.setMerchantId(merchantB);
        b.setCode("TERR-" + tag);
        b.setDisplayName("Territory shop B");
        b.setStatus(ShopStatus.ACTIVE);
        b.setIsDemo(Boolean.TRUE);
        b.setActive(Boolean.TRUE);
        shopB = shops.save(b).getId();

        // BOTH SHOPS CALL THEIR ZONE Z1. That is the point of making the code
        // unique per shop rather than globally: every kirana drawing a map for
        // the first time starts at Z1, and the second one onto the platform
        // must not be told the name is taken.
        zoneAId = insertZone(shopA, "Z1");
        zoneBId = insertZone(shopB, "Z1");
        subzoneAId = insertSubzone(shopA, zoneAId, "Z1A");
        subzoneBId = insertSubzone(shopB, zoneBId, "Z1A");

        riderAId = insertRider(shopA, "Rider A " + tag);
        riderBId = insertRider(shopB, "Rider B " + tag);

        jdbc.update("""
                INSERT INTO customers (full_name, email, mobile_number, password, role, active)
                VALUES (?, ?, ?, 'not-a-real-hash', 'CUSTOMER', true)
                """, "Territory fixture " + tag, tag + "@example.test",
                "9" + (100000000 + (int) (Math.random() * 899999999)));
        customerId = jdbc.queryForObject(
                "SELECT id FROM customers WHERE email = ?", Long.class, tag + "@example.test");

        // The customer's address is stamped in SHOP A's territory - which is
        // how it would really be, having been saved while shopping there.
        jdbc.update("""
                INSERT INTO addresses (customer_id, house_no, street, city, state, pincode,
                                       latitude, longitude, subzone_id, subzone_locked)
                VALUES (?, '1', 'Test Lane', 'Testville', 'TS', '110001', ?, ?, ?, false)
                """, customerId, INSIDE_LAT, INSIDE_LNG, subzoneAId);
        addressId = jdbc.queryForObject(
                "SELECT id FROM addresses WHERE customer_id = ?", Long.class, customerId);

        resolver.invalidate();
    }

    @AfterEach
    void tidyUp() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
        resolver.invalidate();
        jdbc.update("DELETE FROM addresses WHERE id = ?", addressId);
        jdbc.update("DELETE FROM subzone_backup_partners WHERE subzone_id in (?, ?)",
                subzoneAId, subzoneBId);
        jdbc.update("UPDATE delivery_subzones SET primary_partner_id = NULL WHERE id in (?, ?)",
                subzoneAId, subzoneBId);
        jdbc.update("DELETE FROM delivery_subzones WHERE id in (?, ?)", subzoneAId, subzoneBId);
        jdbc.update("DELETE FROM delivery_zones WHERE id in (?, ?)", zoneAId, zoneBId);
        jdbc.update("DELETE FROM delivery_partners WHERE id in (?, ?)", riderAId, riderBId);
        jdbc.update("DELETE FROM customers WHERE id = ?", customerId);
        jdbc.update("DELETE FROM store_operations_settings WHERE shop_id = ?", shopB);
        jdbc.update("DELETE FROM delivery_pricing_settings WHERE shop_id = ?", shopB);
        jdbc.update("DELETE FROM shops WHERE id = ?", shopB);
        jdbc.update("DELETE FROM merchants WHERE id = ?", merchantB);
    }

    // ------------------------------------------------------------- the map

    @Test
    @DisplayName("each shop reads its own zones and subzones and none of the other's")
    void theMapDoesNotCrossShops() {
        List<Long> zonesOfA = TenantContext.runWithin(TenantScope.ofShop(shopA),
                () -> zones.findAll().stream().map(DeliveryZone::getId).toList());
        List<Long> zonesOfB = TenantContext.runWithin(TenantScope.ofShop(shopB),
                () -> zones.findAll().stream().map(DeliveryZone::getId).toList());

        assertTrue(zonesOfA.contains(zoneAId), "Shop A lost sight of its own zone");
        assertTrue(zonesOfB.contains(zoneBId), "Shop B lost sight of its own zone");
        assertFalse(zonesOfA.contains(zoneBId), "Shop A can read Shop B's map");
        assertFalse(zonesOfB.contains(zoneAId), "Shop B can read Shop A's map");
    }

    @Test
    @DisplayName("both shops may call their first zone Z1, because a code belongs to a shop")
    void twoShopsMayUseTheSameTerritoryCode() {
        // Already proved by the fixture inserting both, but stated as its own
        // test because the old global unique index would have made the second
        // merchant onto the platform unable to name their own territories -
        // and that would have looked like a data problem rather than a
        // leftover from there being one shop.
        Long codesNamedZ1 = jdbc.queryForObject(
                "SELECT count(*) FROM delivery_zones WHERE id in (?, ?) AND code = 'Z1'",
                Long.class, zoneAId, zoneBId);
        assertEquals(2L, codesNamedZ1, "both shops must be able to have a zone called Z1");
    }

    @Test
    @DisplayName("a guessed subzone id from another shop is not found, not returned")
    void readingAnotherShopsTerritoryByIdIsRefused() {
        assertTrue(TenantContext.runWithin(TenantScope.ofShop(shopA),
                        () -> subzones.findInScope(subzoneAId)).isPresent(),
                "Shop A cannot open its own territory");
        assertTrue(TenantContext.runWithin(TenantScope.ofShop(shopA),
                        () -> subzones.findInScope(subzoneBId)).isEmpty(),
                "changing the id handed Shop A a territory belonging to Shop B");
    }

    // ----------------------------------------------------------- the cache

    @Test
    @DisplayName("the in-memory map is one per shop, so the first shop to resolve does not answer for the rest")
    void theCachedMapIsNotShared() {
        // Shop A resolves first and fills its map. Before this slice that
        // single cached map answered for every shop in the process.
        Optional<Long> inA = TenantContext.runWithin(TenantScope.ofShop(shopA),
                () -> resolver.resolveSubzoneId(INSIDE_LAT, INSIDE_LNG));
        Optional<Long> inB = TenantContext.runWithin(TenantScope.ofShop(shopB),
                () -> resolver.resolveSubzoneId(INSIDE_LAT, INSIDE_LNG));

        assertEquals(Optional.of(subzoneAId), inA, "Shop A must resolve into its own territory");
        assertEquals(Optional.of(subzoneBId), inB,
                "Shop B resolved into " + inB + " - a point over the SAME ground must land in "
                        + "each shop's OWN territory. Getting Shop A's id here means one cached "
                        + "map is answering for both, which decides who delivers a competitor's "
                        + "order");
    }

    @Test
    @DisplayName("a shop that has drawn nothing gets an empty map, not somebody else's")
    void aShopWithNoMapGetsNoMap() {
        jdbc.update("UPDATE delivery_subzones SET active = false WHERE id = ?", subzoneBId);
        resolver.invalidate();
        try {
            Optional<Long> inB = TenantContext.runWithin(TenantScope.ofShop(shopB),
                    () -> resolver.resolveSubzoneId(INSIDE_LAT, INSIDE_LNG));
            assertTrue(inB.isEmpty(),
                    "a shop with no drawn territory must resolve to nothing and fall back to "
                            + "load-based assignment - inheriting another shop's map is a rider "
                            + "sent somewhere nobody chose");
        } finally {
            jdbc.update("UPDATE delivery_subzones SET active = true WHERE id = ?", subzoneBId);
            resolver.invalidate();
        }
    }

    // --------------------------------------------------------- the pairing

    @Test
    @DisplayName("a shop cannot make another shop's rider its territory's own")
    void aTerritorysRiderMustWorkForThatShop() {
        assertThrows(CrossShopAccessException.class,
                () -> TenantContext.runWithin(TenantScope.platform(),
                        () -> territoryAdmin.setPrimaryPartner(subzoneAId, riderBId)),
                "Shop A pinned Shop B's rider to its own territory. W4: a worker belongs to one "
                        + "shop, and dispatching another merchant's staff is what that decision "
                        + "rules out");
    }

    @Test
    @DisplayName("a shop's own rider is accepted, so the rule refuses crossings and not the feature")
    void aShopsOwnRiderIsAccepted() {
        DeliverySubzone updated = TenantContext.runWithin(TenantScope.ofShop(shopA),
                () -> territoryAdmin.setPrimaryPartner(subzoneAId, riderAId));

        assertNotNull(updated.getPrimaryPartner(), "a shop must still be able to staff its own map");
        assertEquals(riderAId, updated.getPrimaryPartner().getId());
    }

    @Test
    @DisplayName("a named backup rider is held to the same rule as the territory's own")
    void aBackupRiderCannotCrossShopsEither() {
        assertThrows(CrossShopAccessException.class,
                () -> TenantContext.runWithin(TenantScope.platform(),
                        () -> territoryAdmin.setBackupPartners(subzoneAId, List.of(riderBId))),
                "the standing-backup list is a second door into the same decision, and it was "
                        + "open");
    }

    @Test
    @DisplayName("each shop's riders are its own")
    void ridersDoNotCrossShops() {
        List<Long> ofA = TenantContext.runWithin(TenantScope.ofShop(shopA),
                () -> partners.findAll().stream().map(DeliveryPartner::getId).toList());
        assertTrue(ofA.contains(riderAId), "Shop A lost its own rider");
        assertFalse(ofA.contains(riderBId), "Shop A can see Shop B's roster");
    }

    // ------------------------------------- the order's territory, per shop

    @Test
    @DisplayName("an order's territory is resolved in its own shop's map, not read off the address")
    void theOrdersTerritoryIsItsShops() {
        com.gpstore.entity.Address address = TenantContext.runWithin(TenantScope.platform(),
                () -> addresses.findById(addressId).orElseThrow());

        Long forA = TenantContext.runWithin(TenantScope.ofShop(shopA),
                () -> resolver.territoryForDelivery(address).map(DeliverySubzone::getId).orElse(null));
        Long forB = TenantContext.runWithin(TenantScope.ofShop(shopB),
                () -> resolver.territoryForDelivery(address).map(DeliverySubzone::getId).orElse(null));

        assertEquals(subzoneAId, forA,
                "Shop A must use the territory stamped on the address - it is Shop A's, and an "
                        + "administrator may have pinned it");
        assertEquals(subzoneBId, forB,
                "Shop B got " + forB + ". The address carries ONE subzone_id and it is Shop A's; "
                        + "Shop B must resolve its own map rather than inherit a competitor's "
                        + "decision about which territory this house is in");
    }

    @Test
    @DisplayName("an address with no stamp resolves into this shop's map - a behaviour change, stated")
    void anUnstampedAddressResolvesRatherThanFallingBack() {
        // THIS IS A CHANGE TO THE EXISTING SHOP'S BEHAVIOUR, and it is written
        // down rather than left to be discovered.
        //
        // Before this slice the territory of an order was whatever
        // addresses.subzone_id held, and an address saved before the map was
        // drawn - or before anyone ran a re-resolve - had none, so the order
        // dispatched as FALLBACK for ever. That could not survive W4: under a
        // marketplace the stamp belongs to ONE shop, so a second shop would
        // have no territory for any address and every one of its orders would
        // fall back.
        //
        // So the resolver now falls through to this shop's own map. The effect
        // on Shop #1 is that an unstamped address inside a drawn outline is
        // dispatched to its real territory instead of to whoever is least
        // loaded - a rider who knows the streets rather than one who does not.
        // Strictly better, but different, and §12 says a change like that gets
        // named.
        jdbc.update("UPDATE addresses SET subzone_id = NULL WHERE id = ?", addressId);

        com.gpstore.entity.Address address = TenantContext.runWithin(TenantScope.platform(),
                () -> addresses.findById(addressId).orElseThrow());

        Long forA = TenantContext.runWithin(TenantScope.ofShop(shopA),
                () -> resolver.territoryForDelivery(address).map(DeliverySubzone::getId).orElse(null));

        assertEquals(subzoneAId, forA,
                "an unstamped address inside a drawn territory must resolve into it");
    }

    @Test
    @DisplayName("an address outside every outline still resolves to nothing, so it fails closed")
    void anAddressInNoTerritoryStillResolvesToNothing() {
        jdbc.update("UPDATE addresses SET subzone_id = NULL, latitude = 20.0, longitude = 70.0 "
                + "WHERE id = ?", addressId);

        com.gpstore.entity.Address address = TenantContext.runWithin(TenantScope.platform(),
                () -> addresses.findById(addressId).orElseThrow());

        Long forA = TenantContext.runWithin(TenantScope.ofShop(shopA),
                () -> resolver.territoryForDelivery(address).map(DeliverySubzone::getId).orElse(null));

        assertNull(forA,
                "a point in no drawn territory must stay unknown. Pushing it into the nearest "
                        + "one is a rider sent across a river with nothing anywhere saying so");
    }

    @Test
    @DisplayName("a customer can still list their own addresses while shopping at either shop")
    void makingTheMapPrivateDidNotBreakTheAddressList() {
        // THE OUTAGE THIS SLICE NEARLY SHIPPED. An address is a customer's row
        // and spans every shop they buy from, but it carries one subzone_id.
        // While AddressRepository fetch-joined the subzone, listing addresses
        // under Shop B loaded Shop A's territory row and @PostLoad refused it.
        List<com.gpstore.entity.Address> fromB = TenantContext.runWithin(TenantScope.ofShop(shopB),
                () -> addresses.findByCustomerId(customerId));

        assertEquals(1, fromB.size(),
                "a customer shopping at Shop B could not read their own address, because it was "
                        + "stamped in Shop A's territory. Isolation that locks a customer out of "
                        + "their own data is an outage, not a boundary");
        assertEquals(addressId, fromB.get(0).getId());
    }

    // ------------------------------------------------------------ fixtures

    private long insertZone(long shopId, String code) {
        jdbc.update("""
                INSERT INTO delivery_zones (code, name, active, shop_id)
                VALUES (?, ?, true, ?)
                """, code, code + " " + tag, shopId);
        return jdbc.queryForObject(
                "SELECT id FROM delivery_zones WHERE shop_id = ? AND code = ?",
                Long.class, shopId, code);
    }

    private long insertSubzone(long shopId, long zoneId, String code) {
        jdbc.update("""
                INSERT INTO delivery_subzones (zone_id, code, name, boundary, active,
                                               max_concurrent_orders, shop_id)
                VALUES (?, ?, ?, ?, true, 12, ?)
                """, zoneId, code, code + " " + tag, OVERLAPPING_SQUARE, shopId);
        return jdbc.queryForObject(
                "SELECT id FROM delivery_subzones WHERE shop_id = ? AND code = ?",
                Long.class, shopId, code);
    }

    private long insertRider(long shopId, String name) {
        jdbc.update("""
                INSERT INTO delivery_partners (name, mobile, available, active, shop_id)
                VALUES (?, ?, true, true, ?)
                """, name, "8" + (100000000 + (int) (Math.random() * 899999999)), shopId);
        return jdbc.queryForObject(
                "SELECT id FROM delivery_partners WHERE name = ?", Long.class, name);
    }
}
