package com.gpstore.territory;

import com.gpstore.entity.Address;
import com.gpstore.entity.Delivery;
import com.gpstore.entity.Order;
import com.gpstore.repository.DeliveryRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Sequencing the drops inside one territory.
 *
 * The planner takes its deliveries and its clock as arguments precisely so it
 * can be tested like this - a route planner whose output depends on the wall
 * clock and on whatever happens to be in the database is one nobody can write
 * an assertion about. The repository is mocked here because it contributes
 * nothing to the question being asked: given these stops and this moment,
 * what order should they be ridden in?
 */
class TerritoryRouteTest {

    /** The store, and the point every route starts from. */
    private static final double STORE_LAT = 28.6139;
    private static final double STORE_LNG = 77.2090;

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 23, 18, 0);

    private final TerritoryRouteService planner = new TerritoryRouteService(
            Mockito.mock(DeliveryRepository.class), STORE_LAT, STORE_LNG, 20);

    private static long nextId = 1;

    /**
     * A stop at a given offset from the store, promised at a given time.
     *
     * Offsets are in degrees so the fixtures read as "a bit north" rather than
     * as raw coordinates; at these latitudes 0.001 degrees is roughly 110 m,
     * which is a street.
     */
    private Delivery stop(double latOffset, double lngOffset, LocalDateTime promisedBy) {
        Address address = new Address();
        address.setLatitude(STORE_LAT + latOffset);
        address.setLongitude(STORE_LNG + lngOffset);
        address.setHouseNo("H" + nextId);
        address.setArea("Street " + nextId);

        Order order = new Order();
        order.setAddress(address);

        Delivery delivery = new Delivery();
        delivery.setOrder(order);
        delivery.setDeliveryStatus("ASSIGNED");
        delivery.setEstimatedDeliveryTime(promisedBy);
        nextId++;
        return delivery;
    }

    /**
     * Identifies a stop by where it is rather than by its id.
     *
     * Delivery and Order have no id setter - ids come from the database, and
     * adding a setter to an entity so that a unit test can fake one would be
     * letting the test dictate the model. Coordinates identify these fixtures
     * uniquely and are what the planner actually works on.
     */
    private void assertStopIs(TerritoryRouteService.PlannedRoute route, int index, Delivery expected) {
        var actual = route.stops().get(index);
        assertEquals(expected.getOrder().getAddress().getLatitude(), actual.latitude(), 1e-9,
                "stop " + index + " of the route");
        assertEquals(expected.getOrder().getAddress().getLongitude(), actual.longitude(), 1e-9,
                "stop " + index + " of the route");
    }

    /** Far enough out that nothing is urgent. */
    private LocalDateTime relaxed() {
        return NOW.plusHours(2);
    }

    @Test
    @DisplayName("with nothing urgent, the route is nearest-first from the store")
    void nearestNeighbourFromTheStore() {
        Delivery far = stop(0.030, 0, relaxed());
        Delivery near = stop(0.005, 0, relaxed());
        Delivery middle = stop(0.015, 0, relaxed());

        // Deliberately handed over in the WORST order, so passing this cannot
        // be an accident of input ordering.
        var route = planner.plan(1L, List.of(far, middle, near), NOW);

        assertEquals(3, route.stopCount());
        assertStopIs(route, 0, near);
        assertStopIs(route, 1, middle);
        assertStopIs(route, 2, far);
    }

    @Test
    @DisplayName("a promise about to break is ridden first, even if it is the furthest away")
    void urgencyBeatsProximity() {
        // The failure this exists to prevent: a tidy loop that works its way
        // round to the far house forty minutes after the customer was told it
        // would arrive. A promise already made outranks a shorter ride.
        Delivery nearAndRelaxed = stop(0.005, 0, relaxed());
        Delivery farAndDueNow = stop(0.030, 0, NOW.plusMinutes(5));

        var route = planner.plan(1L, List.of(nearAndRelaxed, farAndDueNow), NOW);

        assertStopIs(route, 0, farAndDueNow);
        assertTrue(route.stops().get(0).urgent(),
                "and it has to be MARKED urgent - a stop out of geographic order looks like a "
                        + "bug in the rider's app unless it says why");
        assertFalse(route.stops().get(1).urgent());
    }

    @Test
    @DisplayName("among several urgent drops, the one promised soonest goes first")
    void mostUrgentFirst() {
        Delivery dueIn15 = stop(0.005, 0, NOW.plusMinutes(15));
        Delivery dueIn2 = stop(0.030, 0, NOW.plusMinutes(2));
        Delivery dueIn10 = stop(0.010, 0, NOW.plusMinutes(10));

        var route = planner.plan(1L, List.of(dueIn15, dueIn10, dueIn2), NOW);

        assertStopIs(route, 0, dueIn2);
        assertStopIs(route, 1, dueIn10);
        assertStopIs(route, 2, dueIn15);
    }

    @Test
    @DisplayName("an already-late drop is the most urgent thing there is")
    void alreadyLateSortsFirst() {
        Delivery late = stop(0.030, 0, NOW.minusMinutes(30));
        Delivery dueSoon = stop(0.005, 0, NOW.plusMinutes(10));

        var route = planner.plan(1L, List.of(dueSoon, late), NOW);

        assertStopIs(route, 0, late);
    }

    @Test
    @DisplayName("a drop with no coordinates is left out of the route rather than guessed at")
    void unlocatableStopsAreNotSequenced() {
        Delivery locatable = stop(0.005, 0, relaxed());
        Delivery noCoordinates = stop(0.010, 0, relaxed());
        noCoordinates.getOrder().getAddress().setLatitude(null);
        noCoordinates.getOrder().getAddress().setLongitude(null);

        var route = planner.plan(1L, List.of(locatable, noCoordinates), NOW);

        assertEquals(1, route.stopCount(),
                "pinning an unlocatable drop to the start or the end would be a guess presented "
                        + "as a plan; it stays a real assigned delivery, just not a sequenced one");
        assertStopIs(route, 0, locatable);
    }

    @Test
    @DisplayName("the route reports the distance it actually adds up to")
    void totalDistanceIsTheSumOfTheLegs() {
        Delivery a = stop(0.005, 0, relaxed());
        Delivery b = stop(0.015, 0, relaxed());

        var route = planner.plan(1L, List.of(a, b), NOW);

        double legs = route.stops().stream().mapToDouble(TerritoryRouteService.RouteStop::legKm).sum();
        assertEquals(legs, route.totalKm(), 0.11,
                "the headline number has to be the legs it is made of, within rounding");
        assertTrue(route.totalKm() > 0);
    }

    @Test
    @DisplayName("an empty territory plans an empty route rather than failing")
    void emptyIsFine() {
        var route = planner.plan(1L, List.of(), NOW);

        assertEquals(0, route.stopCount());
        assertEquals(0.0, route.totalKm(), 0.001);
    }

    @Test
    @DisplayName("the first leg is measured from the store, not from the first stop")
    void theRouteStartsAtTheStore() {
        // If the first leg were zero, the route would be pretending the rider
        // begins at the customer's door. The ride out from the shop is real
        // time and has to be in the total.
        Delivery only = stop(0.010, 0, relaxed());

        var route = planner.plan(1L, List.of(only), NOW);

        assertTrue(route.stops().get(0).legKm() > 0.5,
                "roughly 1.1 km at these latitudes; was " + route.stops().get(0).legKm());
    }
}
