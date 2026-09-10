package com.gpstore.order;

import com.gpstore.enums.OrderActor;
import com.gpstore.enums.OrderStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THE ORDER LIFECYCLE, ASSERTED AS A TABLE (§1).
 *
 * <p>Part 3 asks for clear state transitions and for invalid ones to be
 * prevented. The rules used to be a fourteen-clause boolean expression inside
 * a service method - correct, and impossible to check against a diagram. This
 * is the diagram.
 *
 * <p>THE MOST IMPORTANT CASES HERE ARE THE ONES THAT MUST FAIL. A transition
 * table is only worth having if the moves it does not list are actually
 * refused, so most of what follows is the shape of the hole: a delivered
 * order cannot go back on the van, a shop cannot reject an order it has
 * already accepted, a worker cannot confirm one, a customer cannot mark one
 * delivered.
 */
@DisplayName("The order lifecycle")
class OrderLifecycleTest {

    @Nested
    @DisplayName("the ordinary journey")
    class HappyPath {

        @Test
        @DisplayName("runs placed -> accepted -> preparing -> ready -> out -> delivered -> completed")
        void theWholeWay() {
            assertTrue(OrderLifecycle.allows(
                    OrderStatus.PENDING_CONFIRMATION, OrderStatus.CONFIRMED));
            assertTrue(OrderLifecycle.allows(OrderStatus.CONFIRMED, OrderStatus.PACKING));
            assertTrue(OrderLifecycle.allows(OrderStatus.PACKING, OrderStatus.READY_TO_DISPATCH));
            assertTrue(OrderLifecycle.allows(
                    OrderStatus.READY_TO_DISPATCH, OrderStatus.OUT_FOR_DELIVERY));
            assertTrue(OrderLifecycle.allows(OrderStatus.OUT_FOR_DELIVERY, OrderStatus.DELIVERED));
            assertTrue(OrderLifecycle.allows(OrderStatus.DELIVERED, OrderStatus.COMPLETED),
                    "§1 ends at COMPLETED, which DELIVERED is not: a delivered order can still "
                            + "become a return, a replacement or a refund");
        }

        @Test
        @DisplayName("keeps every route the pack-scan flow already used")
        void packedStillWorksBothWays() {
            // PACKED is what a worker's QR scan writes; READY_TO_DISPATCH
            // predates it and is still on live orders and in the admin
            // dropdown. Deleting a state production rows hold breaks every
            // order mid-flight, so both stay and they reach each other.
            assertTrue(OrderLifecycle.allows(OrderStatus.CONFIRMED, OrderStatus.PACKED));
            assertTrue(OrderLifecycle.allows(OrderStatus.PACKING, OrderStatus.PACKED));
            assertTrue(OrderLifecycle.allows(OrderStatus.READY_TO_DISPATCH, OrderStatus.PACKED));
            assertTrue(OrderLifecycle.allows(OrderStatus.PACKED, OrderStatus.READY_TO_DISPATCH));
            assertTrue(OrderLifecycle.allows(OrderStatus.PACKED, OrderStatus.OUT_FOR_DELIVERY));
        }
    }

    @Nested
    @DisplayName("what must not happen")
    class Refused {

        @Test
        @DisplayName("a delivered order does not go back on the van")
        void noGoingBackwards() {
            assertFalse(OrderLifecycle.allows(OrderStatus.DELIVERED, OrderStatus.OUT_FOR_DELIVERY));
            assertFalse(OrderLifecycle.allows(OrderStatus.DELIVERED, OrderStatus.PACKING));
            assertFalse(OrderLifecycle.allows(OrderStatus.DELIVERED, OrderStatus.CANCELLED),
                    "an order that reached the customer is returned or refunded, not cancelled");
            assertFalse(OrderLifecycle.allows(OrderStatus.OUT_FOR_DELIVERY, OrderStatus.PACKING));
        }

        @Test
        @DisplayName("a shop cannot reject an order it has already accepted")
        void rejectionIsOnlyBeforeAcceptance() {
            assertTrue(OrderLifecycle.allows(
                    OrderStatus.PENDING_CONFIRMATION, OrderStatus.REJECTED));

            for (OrderStatus accepted : EnumSet.of(OrderStatus.CONFIRMED, OrderStatus.PACKING,
                    OrderStatus.PACKED, OrderStatus.READY_TO_DISPATCH,
                    OrderStatus.OUT_FOR_DELIVERY)) {
                assertFalse(OrderLifecycle.allows(accepted, OrderStatus.REJECTED),
                        "§2: ONCE A SHOP HAS SAID YES IT OWES THE ORDER. A \"reject\" available "
                                + "after acceptance is a back door out of that promise with a "
                                + "friendlier word on it - " + accepted);
            }
        }

        @Test
        @DisplayName("nothing moves out of a state that is over")
        void terminalIsTerminal() {
            assertTrue(OrderLifecycle.isTerminal(OrderStatus.CANCELLED));
            assertTrue(OrderLifecycle.isTerminal(OrderStatus.REJECTED));
            assertTrue(OrderLifecycle.isTerminal(OrderStatus.COMPLETED));
            assertEquals(Set.of(), OrderLifecycle.nextFrom(OrderStatus.CANCELLED));
            assertEquals(Set.of(), OrderLifecycle.nextFrom(OrderStatus.COMPLETED));
            assertEquals(Set.of(), OrderLifecycle.nextFrom(OrderStatus.REJECTED));
        }
    }

    @Nested
    @DisplayName("a delivery that came back")
    class DeliveryFailed {

        @Test
        @DisplayName("is not the end of the order")
        void itCanGoOutAgain() {
            assertTrue(OrderLifecycle.allows(
                    OrderStatus.OUT_FOR_DELIVERY, OrderStatus.DELIVERY_FAILED));
            assertFalse(OrderLifecycle.isTerminal(OrderStatus.DELIVERY_FAILED),
                    "NOBODY WAS HOME IS NOT A CANCELLED ORDER. Collapsing the two would refund "
                            + "and restock an order the shop is still holding on the shelf.");
            assertTrue(OrderLifecycle.allows(
                    OrderStatus.DELIVERY_FAILED, OrderStatus.OUT_FOR_DELIVERY),
                    "the usual outcome is that it goes out again tomorrow");
            assertTrue(OrderLifecycle.allows(OrderStatus.DELIVERY_FAILED, OrderStatus.DELIVERED));
            assertTrue(OrderLifecycle.allows(OrderStatus.DELIVERY_FAILED, OrderStatus.CANCELLED),
                    "and when it truly cannot be delivered, it can be called off");
        }
    }

    @Nested
    @DisplayName("who may make the move")
    class Actors {

        @Test
        @DisplayName("a worker moves the packet, and does not accept the order")
        void aWorkerCannotConfirm() {
            assertTrue(OrderLifecycle.allows(
                    OrderStatus.OUT_FOR_DELIVERY, OrderStatus.DELIVERED, OrderActor.WORKER));
            assertTrue(OrderLifecycle.allows(
                    OrderStatus.PACKED, OrderStatus.OUT_FOR_DELIVERY, OrderActor.WORKER));

            assertFalse(OrderLifecycle.allows(
                    OrderStatus.PENDING_CONFIRMATION, OrderStatus.CONFIRMED, OrderActor.WORKER),
                    "ACCEPTING AN ORDER IS A COMMERCIAL DECISION, not a task on the round. The "
                            + "shop takes on the obligation (§2); the rider carries the bag.");
            assertFalse(OrderLifecycle.allows(
                    OrderStatus.PENDING_CONFIRMATION, OrderStatus.REJECTED, OrderActor.WORKER));
        }

        @Test
        @DisplayName("a customer calls off an order but does not deliver it")
        void aCustomerCannotDeliver() {
            assertTrue(OrderLifecycle.allows(
                    OrderStatus.PENDING_CONFIRMATION, OrderStatus.CANCELLED, OrderActor.CUSTOMER));
            assertTrue(OrderLifecycle.allows(
                    OrderStatus.CONFIRMED, OrderStatus.CANCELLED, OrderActor.CUSTOMER));

            assertFalse(OrderLifecycle.allows(
                    OrderStatus.OUT_FOR_DELIVERY, OrderStatus.DELIVERED, OrderActor.CUSTOMER),
                    "a customer marking their own order delivered is a customer closing a "
                            + "dispute in their own favour");
            assertFalse(OrderLifecycle.allows(
                    OrderStatus.PENDING_CONFIRMATION, OrderStatus.CONFIRMED, OrderActor.CUSTOMER),
                    "and accepting it on the shop's behalf is worse");
        }

        @Test
        @DisplayName("a customer cannot call one off once the shop is packing it")
        void theCustomerWindowClosesAtPreparation() {
            assertFalse(OrderLifecycle.allows(
                    OrderStatus.PACKING, OrderStatus.CANCELLED, OrderActor.CUSTOMER),
                    "§9: the packet is being made up. Past that it is a conversation with the "
                            + "shop, not a button.");
            assertTrue(OrderLifecycle.allows(
                    OrderStatus.PACKING, OrderStatus.CANCELLED, OrderActor.MERCHANT),
                    "the SHOP can still call it off, and wears the fault for doing so (§12)");
        }

        @Test
        @DisplayName("the screens can ask what to draw, instead of hard-coding a second copy")
        void nextFromIsUsableByTheApps() {
            Set<OrderStatus> merchant =
                    OrderLifecycle.nextFrom(OrderStatus.CONFIRMED, OrderActor.MERCHANT);
            assertTrue(merchant.contains(OrderStatus.PACKING));
            assertTrue(merchant.contains(OrderStatus.CANCELLED));
            assertFalse(merchant.contains(OrderStatus.DELIVERED),
                    "a confirmed order is not one move from delivered, and a button that says "
                            + "so is a button that lies");

            Set<OrderStatus> customer =
                    OrderLifecycle.nextFrom(OrderStatus.CONFIRMED, OrderActor.CUSTOMER);
            assertEquals(Set.of(OrderStatus.CANCELLED), customer,
                    "cancelling is the only thing a customer does to an order");
        }
    }

    @Nested
    @DisplayName("the line §2 draws")
    class Accepted {

        @Test
        @DisplayName("starts at CONFIRMED and never comes back")
        void onceAcceptedAlwaysOwed() {
            assertFalse(OrderLifecycle.isAccepted(OrderStatus.PENDING_CONFIRMATION),
                    "an order the shop has not looked at yet is not owed by anybody");

            for (OrderStatus owed : EnumSet.of(OrderStatus.CONFIRMED, OrderStatus.PACKING,
                    OrderStatus.PACKED, OrderStatus.READY_TO_DISPATCH,
                    OrderStatus.OUT_FOR_DELIVERY, OrderStatus.DELIVERY_FAILED,
                    OrderStatus.DELIVERED, OrderStatus.COMPLETED)) {
                assertTrue(OrderLifecycle.isAccepted(owed),
                        "§2: closing the shop, pausing orders and stopping for the day leave "
                                + "this exactly where it is - " + owed);
            }

            assertFalse(OrderLifecycle.isAccepted(OrderStatus.REJECTED));
            assertFalse(OrderLifecycle.isAccepted(OrderStatus.CANCELLED));
        }
    }
}
