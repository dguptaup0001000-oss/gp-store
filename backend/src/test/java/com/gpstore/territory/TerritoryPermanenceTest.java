package com.gpstore.territory;

import com.gpstore.entity.Address;
import com.gpstore.entity.DeliverySubzone;
import com.gpstore.entity.DeliveryZone;
import com.gpstore.repository.AddressRepository;
import com.gpstore.repository.DeliverySubzoneRepository;
import com.gpstore.repository.DeliveryZoneRepository;
import com.gpstore.service.AddressService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The promise the whole design is built on: a customer's territory does not
 * move on its own.
 *
 * A rider learns Z7B by delivering to the same houses week after week. That
 * only pays off if those houses stay in Z7B - through busy evenings, through
 * quiet ones, through the shop doubling its order volume. These tests are the
 * assertions that stop a future "helpful" optimisation from quietly
 * rebalancing territories by order count and taking that away.
 */
@SpringBootTest(properties = {
        "outbox.initial-delay-ms=3600000",
        "outbox.drain-interval-ms=3600000",
        "payment.expiry-initial-delay-ms=3600000",
        "idempotency.cleanup-initial-delay-ms=3600000",
        "otp.cleanup-initial-delay-ms=3600000",
        "delivery.late-flag-initial-delay-ms=3600000"
})
class TerritoryPermanenceTest {

    private static final String PREFIX = "TP-";
    private static final String CUSTOMER_MARKER = "TERRITORY_PERMANENCE_TEST";

    private static final String WEST = "[[28.60,77.20],[28.60,77.22],[28.62,77.22],[28.62,77.20]]";
    private static final String EAST = "[[28.60,77.22],[28.60,77.24],[28.62,77.24],[28.62,77.22]]";

    private static final double WEST_LAT = 28.610;
    private static final double WEST_LNG = 77.210;

    @Autowired private AddressService addressService;
    @Autowired private AddressRepository addressRepository;
    @Autowired private DeliveryZoneRepository zoneRepository;
    @Autowired private DeliverySubzoneRepository subzoneRepository;
    @Autowired private TerritoryResolver resolver;
    @Autowired private TerritoryAdminService adminService;
    @Autowired private JdbcTemplate jdbc;

    private DeliverySubzone west;
    private DeliverySubzone east;

    @BeforeEach
    void drawTwoTerritories() {
        cleanUp();

        DeliveryZone zone = new DeliveryZone();
        zone.setCode(PREFIX + "Z1");
        zone.setName("Permanence test zone");
        zone.setActive(true);
        zone = zoneRepository.save(zone);

        west = save(zone, "Z1A", WEST);
        east = save(zone, "Z1B", EAST);
        resolver.invalidate();
    }

    @AfterEach
    void cleanUp() {
        // Every address pointing at a fixture territory, not just this file's
        // own. reresolveAllAddresses is GLOBAL by design - it is the
        // administrator's "re-run the map over everybody" button - so calling
        // it here stamps other tests' addresses with these fixtures too, and
        // the subzone rows cannot be deleted while anything still references
        // them. Detaching by foreign key rather than by name is what makes
        // this cleanup complete instead of merely tidy.
        jdbc.update("UPDATE addresses SET subzone_id = NULL WHERE subzone_id IN "
                + "(SELECT id FROM delivery_subzones WHERE code LIKE ?)", PREFIX + "%");
        jdbc.update("UPDATE deliveries SET subzone_id = NULL WHERE subzone_id IN "
                + "(SELECT id FROM delivery_subzones WHERE code LIKE ?)", PREFIX + "%");
        jdbc.update("UPDATE delivery_batches SET subzone_id = NULL WHERE subzone_id IN "
                + "(SELECT id FROM delivery_subzones WHERE code LIKE ?)", PREFIX + "%");
        jdbc.update("DELETE FROM addresses WHERE full_name = ?", CUSTOMER_MARKER);
        jdbc.update("DELETE FROM subzone_neighbours WHERE subzone_id IN "
                + "(SELECT id FROM delivery_subzones WHERE code LIKE ?)", PREFIX + "%");
        jdbc.update("DELETE FROM delivery_subzones WHERE code LIKE ?", PREFIX + "%");
        jdbc.update("DELETE FROM delivery_zones WHERE code LIKE ?", PREFIX + "%");
        resolver.invalidate();
    }

    private DeliverySubzone save(DeliveryZone zone, String code, String boundary) {
        DeliverySubzone subzone = new DeliverySubzone();
        subzone.setZone(zone);
        subzone.setCode(PREFIX + code);
        subzone.setName("Permanence " + code);
        subzone.setBoundary(boundary);
        subzone.setActive(true);
        return subzoneRepository.save(subzone);
    }

    private Address newAddress(double lat, double lng) {
        Address address = new Address();
        address.setFullName(CUSTOMER_MARKER);
        address.setMobileNumber("9000000001");
        address.setHouseNo("12");
        address.setArea("Test Colony");
        address.setCity("Testville");
        // State became a required field when AddressValidator started guarding
        // the save path (V34). This fixture is about territory stamping, not
        // about what a complete address is - it just has to be one now.
        address.setState("Delhi");
        address.setPincode("110001");
        address.setLatitude(lat);
        address.setLongitude(lng);
        return addressService.save(address);
    }

    @Test
    @DisplayName("saving an address stamps its permanent territory")
    void savingStampsTheTerritory() {
        Address saved = newAddress(WEST_LAT, WEST_LNG);

        assertNotNull(saved.getSubzone(), "an address inside a drawn territory must be stamped with it");
        assertEquals(west.getId(), saved.getSubzone().getId());
    }

    @Test
    @DisplayName("the stamp survives a boundary edit - customers do not move on their own")
    void redrawingABoundaryDoesNotMoveExistingCustomers() {
        Address saved = newAddress(WEST_LAT, WEST_LNG);
        Long originallyIn = saved.getSubzone().getId();

        // The administrator redraws the east territory to swallow the west one
        // entirely. Under a system that resolved on read, this customer would
        // silently change rider on their very next order with nothing anywhere
        // recording that it happened.
        east.setBoundary("[[28.59,77.19],[28.59,77.25],[28.63,77.25],[28.63,77.19]]");
        subzoneRepository.save(east);
        resolver.invalidate();

        Address reloaded = addressRepository.findById(saved.getId()).orElseThrow();

        assertEquals(originallyIn, reloaded.getSubzone().getId(),
                "a stamped address must stay where it was stamped until someone deliberately "
                        + "re-resolves it - permanence is the point of stamping at all");
    }

    @Test
    @DisplayName("an administrator can deliberately re-resolve, and only then does anyone move")
    void reresolveIsTheOneDeliberateWayToMoveCustomers() {
        Address saved = newAddress(WEST_LAT, WEST_LNG);
        assertEquals(west.getId(), saved.getSubzone().getId());

        east.setBoundary("[[28.59,77.19],[28.59,77.25],[28.63,77.25],[28.63,77.19]]");
        subzoneRepository.save(east);
        // And the west territory is withdrawn, so the point now falls only in
        // the enlarged east one.
        west.setActive(false);
        subzoneRepository.save(west);
        resolver.invalidate();

        adminService.reresolveAllAddresses(200);

        Address reloaded = addressRepository.findById(saved.getId()).orElseThrow();
        assertEquals(east.getId(), reloaded.getSubzone().getId(),
                "an explicit re-resolve is exactly what SHOULD move customers - that is the "
                        + "difference between a map being edited and a map drifting");
    }

    @Test
    @DisplayName("a hand-pinned address is never moved by a re-resolve")
    void pinnedAddressesOutrankTheMap() {
        // The house on the wrong side of the boundary road; the colony whose
        // only usable gate opens into the next territory. The map cannot know.
        // A person can, and when they say so it has to stick - including
        // through the one operation that deliberately moves everyone else.
        Address saved = newAddress(WEST_LAT, WEST_LNG);
        adminService.pinAddress(saved.getId(), east.getId());

        adminService.reresolveAllAddresses(200);

        Address reloaded = addressRepository.findById(saved.getId()).orElseThrow();
        assertEquals(east.getId(), reloaded.getSubzone().getId(),
                "a pinned address must keep the territory a human chose for it");
        assertTrue(reloaded.getSubzoneLocked());
    }

    @Test
    @DisplayName("an address outside every territory is stamped with none, not with the nearest")
    void outsideTheMapIsNullNotAGuess() {
        Address saved = newAddress(1.0, 1.0);

        assertNull(saved.getSubzone(),
                "pushing an unmatched address into the nearest territory would send a rider "
                        + "somewhere on no evidence; null is answerable and shows up as a FALLBACK");
    }

    @Test
    @DisplayName("an address with no coordinates is stamped with none")
    void noCoordinatesMeansNoTerritory() {
        Address saved = newAddress(0, 0);
        saved.setLatitude(null);
        saved.setLongitude(null);
        Address resaved = addressService.save(saved);

        assertNull(resaved.getSubzone());
    }

    @Test
    @DisplayName("correcting a wrong pin does move the address - permanence is not stubbornness")
    void editingCoordinatesRestamps() {
        // The distinction that matters: boundaries must not move under a
        // customer, but a customer who tells us their pin was two streets out
        // has genuinely told us something new.
        Address saved = newAddress(WEST_LAT, WEST_LNG);
        assertEquals(west.getId(), saved.getSubzone().getId());

        saved.setLatitude(28.610);
        saved.setLongitude(77.230); // now in the east territory
        Address moved = addressService.updateAddress(saved.getId(), saved);

        assertEquals(east.getId(), moved.getSubzone().getId());
    }

    @Test
    @DisplayName("overlapping territories are reported rather than silently resolved")
    void overlapsAreVisible() {
        // Two polygons claiming one point is a configuration mistake that
        // otherwise surfaces months later as a customer whose rider changes
        // for no visible reason. The admin resolve endpoint says so.
        east.setBoundary("[[28.59,77.19],[28.59,77.25],[28.63,77.25],[28.63,77.19]]");
        subzoneRepository.save(east);
        resolver.invalidate();

        var matches = resolver.findOverlaps(WEST_LAT, WEST_LNG);

        assertEquals(2, matches.size(),
                "both territories contain this point and an administrator needs to be told; "
                        + "matched: " + matches);
        assertTrue(resolver.resolve(WEST_LAT, WEST_LNG).isEmpty(),
                "overlapping polygons must fail closed rather than pick a winner");
    }
}
