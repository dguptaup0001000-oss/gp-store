package com.gpstore.order;

import com.gpstore.entity.Address;
import com.gpstore.entity.Order;
import com.gpstore.worker.WorkerOrderView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * An order goes where it was going when it was placed.
 *
 * THE BUG THIS PINS. orders.address_id is a foreign key to the customer's
 * LIVE saved address, and AddressService.updateAddress rewrites that row in
 * place - latitude and longitude included. So a customer who moved house and
 * corrected their saved address silently changed the destination of every
 * order still pointing at it, including one already packed and on a bike:
 * WorkerOrderView read order.getAddress(), so the rider's screen and their
 * Navigate button both followed the edit to the new house.
 *
 * These tests mutate the address object the order still references - which is
 * exactly what the update path does - and assert the order does not move.
 *
 * Deliberately plain unit tests. The invariant is about which object a value
 * is read from, and a Spring context would add a database, an outbox worker
 * and a schema to a question none of them are part of.
 */
class OrderLocationSnapshotTest {

    private static Address gorakhpurHouse42() {
        Address a = new Address();
        a.setId(1L);
        a.setFullName("Deepak Kumar");
        a.setMobileNumber("9000000000");
        a.setHouseNo("42");
        a.setArea("Gupta Nagar");
        a.setCity("Gorakhpur");
        a.setState("Uttar Pradesh");
        a.setPincode("273001");
        a.setLandmark("Near Gupta Medical Store");
        a.setDeliveryInstructions("Enter from the side lane.");
        a.setLatitude(26.7606);
        a.setLongitude(83.3732);
        return a;
    }

    private static Order orderTo(Address address) {
        Order order = new Order();
        order.setOrderNumber("GP10254");
        order.setAddress(address);
        order.captureDeliverySnapshot(address, LocalDateTime.of(2026, 8, 31, 10, 0));
        return order;
    }

    @Test
    @DisplayName("editing the saved address does not move an order already placed")
    void editingTheAddressDoesNotRedirectTheOrder() {
        Address saved = gorakhpurHouse42();
        Order order = orderTo(saved);

        // The customer moves house and corrects the same saved address. This
        // is the identical object graph the update path mutates.
        saved.setHouseNo("7");
        saved.setArea("Civil Lines");
        saved.setCity("Lucknow");
        saved.setPincode("226001");
        saved.setLandmark("Opposite the bus stand");
        saved.setDeliveryInstructions("Call on arrival.");
        saved.setLatitude(26.8467);
        saved.setLongitude(80.9462);

        // The order still goes to House 42, Gorakhpur.
        assertEquals("42", order.getDeliveryHouseNo());
        assertEquals("Gupta Nagar", order.getDeliveryArea());
        assertEquals("Gorakhpur", order.getDeliveryCity());
        assertEquals("273001", order.getDeliveryPincode());
        assertEquals("Near Gupta Medical Store", order.getDeliveryLandmark());
        assertEquals("Enter from the side lane.", order.getDeliveryInstructions());
        assertEquals(26.7606, order.getDeliveryLatitude());
        assertEquals(83.3732, order.getDeliveryLongitude());
    }

    @Test
    @DisplayName("the rider navigates to the snapshot, not to where the customer now lives")
    void navigationFollowsTheSnapshot() {
        Address saved = gorakhpurHouse42();
        Order order = orderTo(saved);

        saved.setLatitude(26.8467);
        saved.setLongitude(80.9462);

        // This is the whole point: Navigate opens these two numbers.
        assertEquals(26.7606, order.navigationLatitude());
        assertEquals(83.3732, order.navigationLongitude());
    }

    @Test
    @DisplayName("the worker's screen shows the snapshot destination after an address edit")
    void workerViewShowsTheSnapshot() {
        Address saved = gorakhpurHouse42();
        Order order = orderTo(saved);

        saved.setHouseNo("7");
        saved.setCity("Lucknow");
        saved.setLatitude(26.8467);
        saved.setLongitude(80.9462);
        saved.setLandmark("Opposite the bus stand");

        WorkerOrderView view = WorkerOrderView.of(order, null, null, List.of());

        assertEquals(26.7606, view.latitude());
        assertEquals(83.3732, view.longitude());
        assertTrue(view.deliveryAddress().contains("42"), view.deliveryAddress());
        assertTrue(view.deliveryAddress().contains("Gorakhpur"), view.deliveryAddress());
        assertFalse(view.deliveryAddress().contains("Lucknow"), view.deliveryAddress());

        // Landmark and instructions are their own fields now, not glued into
        // the address line - a rider reading one run-on string loses them.
        assertEquals("Near Gupta Medical Store", view.landmark());
        assertEquals("Enter from the side lane.", view.deliveryInstructions());
    }

    @Test
    @DisplayName("a pre-V34 order with no snapshot still shows the linked address")
    void fallsBackForOldOrders() {
        // The V34 backfill could not reconstruct every historical order. For
        // those the live address is the best answer available, and showing a
        // rider nothing would be worse than showing them something stale.
        Address saved = gorakhpurHouse42();
        Order legacy = new Order();
        legacy.setOrderNumber("GP00001");
        legacy.setAddress(saved);
        // No captureDeliverySnapshot call - this is what an old row looks like.

        assertNull(legacy.getDeliverySnapshotAt());
        assertEquals(26.7606, legacy.navigationLatitude());

        WorkerOrderView view = WorkerOrderView.of(legacy, null, null, List.of());
        assertEquals(26.7606, view.latitude());
        assertTrue(view.deliveryAddress().contains("Gupta Nagar"), view.deliveryAddress());
        assertEquals("Near Gupta Medical Store", view.landmark());
    }

    @Test
    @DisplayName("the address line skips parts that do not exist rather than printing ', ,'")
    void buildsACleanAddressLine() {
        Address sparse = new Address();
        sparse.setHouseNo("42");
        sparse.setCity("Gorakhpur");
        sparse.setPincode("273001");
        // No building, floor, street or area.

        Order order = orderTo(sparse);
        WorkerOrderView view = WorkerOrderView.of(order, null, null, List.of());

        assertEquals("42, Gorakhpur - 273001", view.deliveryAddress());
    }

    @Test
    @DisplayName("a confirmed formatted address is shown verbatim, not reassembled")
    void prefersTheConfirmedFormattedAddress() {
        // It is the string the customer actually saw and agreed to on the map.
        // Rebuilding it from the parts would show the rider a different one.
        Address saved = gorakhpurHouse42();
        saved.setFormattedAddress("House 42, Gupta Nagar, Gorakhpur, Uttar Pradesh 273001");

        Order order = orderTo(saved);
        WorkerOrderView view = WorkerOrderView.of(order, null, null, List.of());

        assertEquals("House 42, Gupta Nagar, Gorakhpur, Uttar Pradesh 273001",
                view.deliveryAddress());
    }

    @Test
    @DisplayName("the snapshot records when it was taken, so a backfilled row is distinguishable")
    void recordsWhenItWasTaken() {
        Order order = orderTo(gorakhpurHouse42());
        assertEquals(LocalDateTime.of(2026, 8, 31, 10, 0), order.getDeliverySnapshotAt());
    }
}
