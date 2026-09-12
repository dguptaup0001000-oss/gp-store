package com.gpstore.order;

import com.gpstore.entity.Order;
import com.gpstore.enums.OrderActor;
import com.gpstore.enums.OrderStatus;
import com.gpstore.exception.ConflictException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THE THINGS THAT COULD HAPPEN WHILE FOUR SERVICES WROTE ORDER STATUS
 * WITHOUT ASKING.
 *
 * <p>OrderLifecycle has held the transition table for a while and
 * OrderService consulted it correctly. DeliveryService, WorkerScanService and
 * PaymentService did not - they called {@code order.setOrderStatus(...)}
 * directly, each with its own hand-written guard or none at all. This pins
 * the cases that let through, at the level the fix is made: a pure function,
 * no container, no database, so a reader can check it against the diagram.
 *
 * <p>EVERY TEST HERE FAILS IF {@link OrderStatusChange} STOPS ASKING
 * {@link OrderLifecycle}. That is the point of routing through one door: the
 * table is the only authority, and there is now nowhere else for a second
 * opinion to live.
 */
class OneDoorForOrderStatusTest {

    private static Order orderIn(OrderStatus status) {
        Order order = new Order();
        order.setOrderStatus(status);
        return order;
    }

    @Nested
    @DisplayName("a cancelled order cannot be delivered")
    class TheResurrection {

        /**
         * THE WORST OF THE THREE. The customer cancelled, the money went
         * back, and then somebody closed the delivery row - which wrote
         * DELIVERED straight onto the order, past every rule.
         */
        @Test
        @DisplayName("a worker closing the delivery cannot deliver a cancelled order")
        void deliveringACancelledOrderIsRefused() {
            Order order = orderIn(OrderStatus.CANCELLED);

            ConflictException refused = assertThrows(ConflictException.class,
                    () -> OrderStatusChange.move(order, OrderStatus.DELIVERED, OrderActor.WORKER));

            assertTrue(refused.getMessage().contains("CANCELLED"), refused.getMessage());
            assertEquals(OrderStatus.CANCELLED, order.getOrderStatus());
        }

        @Test
        @DisplayName("nor can it be sent back out for delivery")
        void sendingACancelledOrderOutIsRefused() {
            Order order = orderIn(OrderStatus.CANCELLED);

            assertThrows(ConflictException.class, () -> OrderStatusChange.move(
                    order, OrderStatus.OUT_FOR_DELIVERY, OrderActor.WORKER));
            assertEquals(OrderStatus.CANCELLED, order.getOrderStatus());
        }

        /**
         * THE POSITIVE CONTROL. A refusal proves nothing if the same call is
         * refused for every order, so the legitimate move has to work.
         */
        @Test
        @DisplayName("but a packed order still goes out and still arrives")
        void theOrdinaryJourneyIsUntouched() {
            Order order = orderIn(OrderStatus.PACKED);

            OrderStatusChange.move(order, OrderStatus.OUT_FOR_DELIVERY, OrderActor.WORKER);
            assertEquals(OrderStatus.OUT_FOR_DELIVERY, order.getOrderStatus());

            OrderStatusChange.move(order, OrderStatus.DELIVERED, OrderActor.WORKER);
            assertEquals(OrderStatus.DELIVERED, order.getOrderStatus());
        }
    }

    @Nested
    @DisplayName("the stale-payment sweep cannot cancel what is already over")
    class TheSweep {

        /**
         * The old guard was "not CANCELLED and not DELIVERED". COMPLETED and
         * REJECTED are neither, and both are terminal - so an order the
         * customer had finished with could be cancelled out from under them
         * by a payment row nobody ever completed.
         */
        @Test
        @DisplayName("a completed order is left alone")
        void completedIsNotCancellable() {
            Order order = orderIn(OrderStatus.COMPLETED);

            assertFalse(OrderStatusChange.canMove(order, OrderStatus.CANCELLED, OrderActor.SYSTEM));
            assertThrows(ConflictException.class, () -> OrderStatusChange.move(
                    order, OrderStatus.CANCELLED, OrderActor.SYSTEM));
            assertEquals(OrderStatus.COMPLETED, order.getOrderStatus());
        }

        @Test
        @DisplayName("a rejected order is left alone")
        void rejectedIsNotCancellable() {
            Order order = orderIn(OrderStatus.REJECTED);

            assertFalse(OrderStatusChange.canMove(order, OrderStatus.CANCELLED, OrderActor.SYSTEM));
            assertEquals(OrderStatus.REJECTED, order.getOrderStatus());
        }

        @Test
        @DisplayName("an abandoned pending order is still cancelled, which is the point of the sweep")
        void thePendingOrderTheSweepExistsForIsStillCancelled() {
            Order order = orderIn(OrderStatus.PENDING_CONFIRMATION);

            assertTrue(OrderStatusChange.canMove(order, OrderStatus.CANCELLED, OrderActor.SYSTEM));
            OrderStatusChange.move(order, OrderStatus.CANCELLED, OrderActor.SYSTEM);
            assertEquals(OrderStatus.CANCELLED, order.getOrderStatus());
        }
    }

    @Nested
    @DisplayName("who is asking still matters")
    class TheActor {

        @Test
        @DisplayName("a worker cannot confirm an order")
        void aWorkerMayNotConfirm() {
            Order order = orderIn(OrderStatus.PENDING_CONFIRMATION);

            ConflictException refused = assertThrows(ConflictException.class,
                    () -> OrderStatusChange.move(order, OrderStatus.CONFIRMED, OrderActor.WORKER));

            // The two refusals read differently on purpose: this one is an
            // authorisation answer, not "an order cannot go there".
            assertTrue(refused.getMessage().contains("may not move"), refused.getMessage());
        }

        @Test
        @DisplayName("a customer cannot mark their own order delivered")
        void aCustomerMayNotDeliver() {
            Order order = orderIn(OrderStatus.OUT_FOR_DELIVERY);

            assertThrows(ConflictException.class, () -> OrderStatusChange.move(
                    order, OrderStatus.DELIVERED, OrderActor.CUSTOMER));
            assertEquals(OrderStatus.OUT_FOR_DELIVERY, order.getOrderStatus());
        }

        @Test
        @DisplayName("a merchant may confirm one, which is the control")
        void aMerchantMayConfirm() {
            Order order = orderIn(OrderStatus.PENDING_CONFIRMATION);

            OrderStatusChange.move(order, OrderStatus.CONFIRMED, OrderActor.MERCHANT);
            assertEquals(OrderStatus.CONFIRMED, order.getOrderStatus());
        }
    }

    @Nested
    @DisplayName("retries must not become failures")
    class TheRetry {

        /**
         * A duplicate webhook, a retried request and a double-tapped button
         * all arrive as "set it to what it already is". Throwing at those
         * would turn every idempotent caller in the system into a failing
         * one, which is a worse bug than the one being fixed.
         */
        @Test
        @DisplayName("setting a status to what it already is does nothing and does not throw")
        void reassertingIsANoOp() {
            Order order = orderIn(OrderStatus.DELIVERED);

            OrderStatusChange.move(order, OrderStatus.DELIVERED, OrderActor.WORKER);

            assertEquals(OrderStatus.DELIVERED, order.getOrderStatus());
            assertTrue(OrderStatusChange.canMove(order, OrderStatus.DELIVERED, OrderActor.CUSTOMER),
                    "even an actor with no rights may re-assert a state that is already set");
        }
    }
}
