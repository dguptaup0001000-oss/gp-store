package com.gpstore.service;

import com.gpstore.entity.*;
import com.gpstore.enums.OrderStatus;
import com.gpstore.exception.ConflictException;
import com.gpstore.repository.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * An order's status is the shop's and the customer's shared story of where
 * their groceries are. These assertions are about the ways that story could
 * be made to skip, reverse, or fork.
 *
 * The transition rules were already implemented and already correct. What
 * was missing was anything that would notice if someone loosened them - the
 * rule lives in one boolean expression, and adding a case to it is a
 * one-line change that no existing test would have caught.
 */
@SpringBootTest(properties = {
        "outbox.initial-delay-ms=3600000",
        "outbox.drain-interval-ms=3600000",
        "payment.expiry-initial-delay-ms=3600000",
        "idempotency.cleanup-initial-delay-ms=3600000",
        "otp.cleanup-initial-delay-ms=3600000",
        "delivery.late-flag-initial-delay-ms=3600000"
})
class OrderStatusStateMachineTest {

    @Autowired private OrderService orderService;
    @Autowired private OrderRepository orderRepository;
    @Autowired private CustomerRepository customerRepository;

    @Test
    @DisplayName("The happy path walks one step at a time, all the way to delivered")
    void forwardPathIsAllowed() {
        Long orderId = newOrder(OrderStatus.PENDING_CONFIRMATION);

        orderService.updateOrderStatus(orderId, OrderStatus.CONFIRMED);
        orderService.updateOrderStatus(orderId, OrderStatus.PACKING);
        orderService.updateOrderStatus(orderId, OrderStatus.READY_TO_DISPATCH);
        orderService.updateOrderStatus(orderId, OrderStatus.OUT_FOR_DELIVERY);
        orderService.updateOrderStatus(orderId, OrderStatus.DELIVERED);

        assertEquals(OrderStatus.DELIVERED, statusOf(orderId));
    }

    @Test
    @DisplayName("A stage cannot be skipped")
    void skippingAStageIsRejected() {
        // The customer's tracking screen would jump from "we have your
        // order" to "out for delivery" for an order nobody had packed.
        Long orderId = newOrder(OrderStatus.PENDING_CONFIRMATION);

        assertThrows(ConflictException.class,
                () -> orderService.updateOrderStatus(orderId, OrderStatus.OUT_FOR_DELIVERY));
        assertThrows(ConflictException.class,
                () -> orderService.updateOrderStatus(orderId, OrderStatus.DELIVERED));

        assertEquals(OrderStatus.PENDING_CONFIRMATION, statusOf(orderId),
                "A rejected transition must leave the order exactly as it was");
    }

    @Test
    @DisplayName("An order cannot move backwards")
    void goingBackwardsIsRejected() {
        Long orderId = newOrder(OrderStatus.PENDING_CONFIRMATION);
        orderService.updateOrderStatus(orderId, OrderStatus.CONFIRMED);
        orderService.updateOrderStatus(orderId, OrderStatus.PACKING);

        assertThrows(ConflictException.class,
                () -> orderService.updateOrderStatus(orderId, OrderStatus.CONFIRMED));
        assertEquals(OrderStatus.PACKING, statusOf(orderId));
    }

    @Test
    @DisplayName("A repeated request for the status it already has is rejected, not silently accepted")
    void samestatusAgainIsRejected() {
        // This is the double-tap and the network retry. Treating it as a
        // no-op would be defensible; treating it as SUCCESS would not, and
        // the caller needs to be able to tell the difference.
        Long orderId = newOrder(OrderStatus.PENDING_CONFIRMATION);
        orderService.updateOrderStatus(orderId, OrderStatus.CONFIRMED);

        assertThrows(ConflictException.class,
                () -> orderService.updateOrderStatus(orderId, OrderStatus.CONFIRMED));
        assertEquals(OrderStatus.CONFIRMED, statusOf(orderId));
    }

    @Test
    @DisplayName("Delivered is terminal - nothing moves it, in any direction")
    void deliveredIsTerminal() {
        Long orderId = newOrder(OrderStatus.DELIVERED);

        for (OrderStatus target : EnumSet.allOf(OrderStatus.class)) {
            assertThrows(ConflictException.class,
                    () -> orderService.updateOrderStatus(orderId, target),
                    "Delivered must not be movable to " + target);
        }
        assertEquals(OrderStatus.DELIVERED, statusOf(orderId));
    }

    @Test
    @DisplayName("Cancelled is terminal - a cancelled order cannot be revived into the delivery flow")
    void cancelledIsTerminal() {
        // Otherwise a stale admin client, or a retried request queued before
        // the cancellation, could resurrect an order whose stock has already
        // been returned to the shelf.
        Long orderId = newOrder(OrderStatus.CANCELLED);

        for (OrderStatus target : EnumSet.allOf(OrderStatus.class)) {
            assertThrows(ConflictException.class,
                    () -> orderService.updateOrderStatus(orderId, target),
                    "Cancelled must not be movable to " + target);
        }
        assertEquals(OrderStatus.CANCELLED, statusOf(orderId));
    }

    @Test
    @DisplayName("Every transition outside the one legal path is refused")
    void everyOtherTransitionIsRefused() {
        // Enumerated rather than sampled: the rule is a single boolean
        // expression, and someone adding one clause to it should have to
        // change this test deliberately rather than get away with it.
        for (OrderStatus from : EnumSet.of(
                OrderStatus.PENDING_CONFIRMATION, OrderStatus.CONFIRMED,
                OrderStatus.PACKING, OrderStatus.PACKED, OrderStatus.READY_TO_DISPATCH,
                OrderStatus.OUT_FOR_DELIVERY)) {

            for (OrderStatus to : EnumSet.allOf(OrderStatus.class)) {
                if (isTheOneLegalStep(from, to)) continue;

                Long orderId = newOrder(from);
                assertThrows(ConflictException.class,
                        () -> orderService.updateOrderStatus(orderId, to),
                        from + " -> " + to + " must be refused");
            }
        }
    }

    @Test
    @DisplayName("Two admins pressing the same button at once: one wins, the other is told why")
    void simultaneousAdminActionsDoNotBothApply() throws InterruptedException {
        // The row is locked FOR UPDATE, so the loser re-reads the ALREADY
        // ADVANCED status after the winner commits and fails the transition
        // check - rather than both reading PENDING_CONFIRMATION and both
        // applying a change.
        Long orderId = newOrder(OrderStatus.PENDING_CONFIRMATION);

        int contenders = 8;
        ExecutorService pool = Executors.newFixedThreadPool(contenders);
        CountDownLatch ready = new CountDownLatch(contenders);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(contenders);
        AtomicInteger succeeded = new AtomicInteger();

        for (int i = 0; i < contenders; i++) {
            pool.submit(() -> {
                try {
                    ready.countDown();
                    go.await();
                    orderService.updateOrderStatus(orderId, OrderStatus.CONFIRMED);
                    succeeded.incrementAndGet();
                } catch (Exception expectedForLosers) {
                    // ConflictException once the winner has advanced it.
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await(10, TimeUnit.SECONDS);
        go.countDown();
        assertTrue(done.await(60, TimeUnit.SECONDS));
        pool.shutdown();

        assertEquals(1, succeeded.get(),
                "Exactly one concurrent status update may apply");
        assertEquals(OrderStatus.CONFIRMED, statusOf(orderId));
    }

    /**
     * Every transition the order flow permits.
     *
     * It stopped being a single line the day a worker's QR scan needed a state
     * of its own. PACKED is what that scan writes, and it sits BESIDE
     * READY_TO_DISPATCH rather than replacing it - the older state is still
     * reachable from the admin status dropdown and is still on live orders, and
     * deleting a state that production rows hold breaks every order mid-flight.
     *
     * The pair can be reached from either direction (PACKED -> READY_TO_DISPATCH
     * and back) because they describe the same operational moment under two
     * names, and an admin correcting one to the other is not a mistake worth
     * refusing.
     */
    private static boolean isTheOneLegalStep(OrderStatus from, OrderStatus to) {
        return (from == OrderStatus.PENDING_CONFIRMATION && to == OrderStatus.CONFIRMED)
                || (from == OrderStatus.CONFIRMED && to == OrderStatus.PACKING)
                || (from == OrderStatus.PACKING && to == OrderStatus.READY_TO_DISPATCH)
                || (from == OrderStatus.READY_TO_DISPATCH && to == OrderStatus.OUT_FOR_DELIVERY)
                || (from == OrderStatus.OUT_FOR_DELIVERY && to == OrderStatus.DELIVERED)
                // The worker pack-scan path.
                || (from == OrderStatus.CONFIRMED && to == OrderStatus.PACKED)
                || (from == OrderStatus.PACKING && to == OrderStatus.PACKED)
                || (from == OrderStatus.READY_TO_DISPATCH && to == OrderStatus.PACKED)
                || (from == OrderStatus.PACKED && to == OrderStatus.OUT_FOR_DELIVERY)
                || (from == OrderStatus.PACKED && to == OrderStatus.READY_TO_DISPATCH);
    }

    private OrderStatus statusOf(Long orderId) {
        return orderRepository.findById(orderId).orElseThrow().getOrderStatus();
    }

    private Long newOrder(OrderStatus status) {
        Customer customer = new Customer();
        customer.setFullName("State Machine Customer");
        customer.setEmail("state-" + System.nanoTime() + "@example.com");
        customer.setMobileNumber("9" + String.valueOf(System.nanoTime()).substring(0, 9));
        customer.setPassword("irrelevant-for-this-test");
        customer.setEnabled(true);
        customer.setActive(true);
        customer = customerRepository.save(customer);

        Order order = new Order();
        order.setCustomer(customer);
        order.setOrderStatus(status);
        order.setTotalAmount(new BigDecimal("100.00"));
        order.setOrderNumber("SM-" + System.nanoTime());
        order.setOrderDate(java.time.LocalDateTime.now());
        return orderRepository.save(order).getId();
    }
}
