package com.gpstore.payment;

import com.gpstore.dto.response.OrderDetailResponse;
import com.gpstore.dto.OrderResponse;
import com.gpstore.entity.Customer;
import com.gpstore.entity.Order;
import com.gpstore.entity.Payment;
import com.gpstore.entity.Role;
import com.gpstore.enums.OrderStatus;
import com.gpstore.enums.PaymentMethod;
import com.gpstore.enums.PaymentStatus;
import com.gpstore.repository.CustomerRepository;
import com.gpstore.repository.OrderRepository;
import com.gpstore.repository.PaymentRepository;
import com.gpstore.service.OrderService;
import com.gpstore.service.PaymentService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The money arrived. Every screen must say so.
 *
 * THE BUG, AS THE SHOP SAW IT: a rider collects the cash, the payment row
 * moves to COD_RECEIVED, and the admin's order screen goes on saying
 * "COD PENDING" forever. The order's own payment_status column is a SECOND
 * copy of the status, written once at checkout and never touched again -
 * twelve places change a Payment's status and none of them updates the
 * order's copy. Every list and detail response read that stale copy.
 *
 * That is not cosmetic. It is the screen the shop uses to know which
 * deliveries still owe money, so a settled order sitting in "pending" is a
 * rider being asked for money they already handed in.
 *
 * The fix is to stop keeping a second copy of the truth: the payment row is
 * the answer, and these responses read it. The order column stays for orders
 * that predate payment rows, as the fallback it always was.
 */
@SpringBootTest(properties = {
        "outbox.initial-delay-ms=3600000",
        "outbox.drain-interval-ms=3600000",
        "payment.expiry-initial-delay-ms=3600000",
        "idempotency.cleanup-initial-delay-ms=3600000",
        "otp.cleanup-initial-delay-ms=3600000",
        "delivery.late-flag-initial-delay-ms=3600000"
})
@DisplayName("A collected COD stops showing as pending")
class CollectedCodStopsShowingAsPendingTest {

    @Autowired private PaymentService paymentService;
    @Autowired private OrderService orderService;
    @Autowired private CustomerRepository customers;
    @Autowired private OrderRepository orders;
    @Autowired private PaymentRepository payments;

    private static final BigDecimal DUE = new BigDecimal("640.00");

    private Customer customer;

    // isAdmin=true: who may settle is covered by the worker-ownership tests.
    // These are about what the shop is then shown.
    private Order codOrder() {
        Customer c = new Customer();
        c.setFullName("A Customer");
        c.setEmail("codview-" + System.nanoTime() + "@example.com");
        c.setMobileNumber("9" + String.format("%09d", System.nanoTime() % 1_000_000_000L));
        c.setPassword("x");
        c.setRole(Role.CUSTOMER);
        c.setActive(true);
        c.setEnabled(true);
        customer = customers.save(c);

        Order o = new Order();
        o.setOrderNumber("CODVIEW-" + System.nanoTime());
        o.setCustomer(customer);
        o.setTotalAmount(DUE);
        o.setOrderStatus(OrderStatus.OUT_FOR_DELIVERY);
        // Exactly what placeOrder writes, and never updates again.
        o.setPaymentStatus(PaymentStatus.COD_PENDING);
        o.setOrderDate(LocalDateTime.now());
        o.setActive(true);
        o = orders.save(o);

        Payment p = new Payment();
        p.setOrder(o);
        p.setAmount(DUE);
        p.setPaymentMethod(PaymentMethod.COD);
        p.setPaymentStatus(PaymentStatus.COD_PENDING);
        p.setActive(true);
        payments.save(p);
        return o;
    }

    @Test
    @DisplayName("order detail reports the payment's status, not checkout's copy")
    void detailShowsTheMoneyArrived() {
        Order order = codOrder();
        paymentService.completeCodPayment(order.getId(), 7L, true,
                new BigDecimal("400.00"), new BigDecimal("240.00"));

        OrderDetailResponse detail =
                orderService.getOwnedOrderDetail(order.getId(), customer.getId(), true);

        assertEquals("COD_RECEIVED", detail.getPaymentStatus(),
                "the shop was still being told to collect money it already has");
    }

    @Test
    @DisplayName("the admin order list agrees with the order screen")
    void listShowsTheMoneyArrived() {
        Order order = codOrder();
        paymentService.completeCodPayment(order.getId(), 7L, true, DUE, BigDecimal.ZERO);

        OrderResponse row = orderService
                .getAllOrdersForAdmin(PageRequest.of(0, 50))
                .getContent().stream()
                .filter(r -> order.getId().equals(r.getOrderId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the order is not on the first page"));

        assertEquals("COD_RECEIVED", row.getPaymentStatus());
    }

    @Test
    @DisplayName("how the money arrived is on the order, not only in the payments screen")
    void detailCarriesTheSplit() {
        Order order = codOrder();
        paymentService.completeCodPayment(order.getId(), 7L, true,
                new BigDecimal("400.00"), new BigDecimal("240.00"));

        OrderDetailResponse detail =
                orderService.getOwnedOrderDetail(order.getId(), customer.getId(), true);

        assertNotNull(detail.getCodCashAmount(), "the split is what a cash count is reconciled against");
        assertEquals(0, new BigDecimal("400.00").compareTo(detail.getCodCashAmount()));
        assertEquals(0, new BigDecimal("240.00").compareTo(detail.getCodUpiAmount()));
        assertNotNull(detail.getCodCollectedAt());
    }

    @Test
    @DisplayName("an uncollected COD still reads as pending")
    void anUncollectedCodIsStillPending() {
        // The fix must not make everything look paid.
        Order order = codOrder();

        OrderDetailResponse detail =
                orderService.getOwnedOrderDetail(order.getId(), customer.getId(), true);

        assertEquals("COD_PENDING", detail.getPaymentStatus());
        assertNull(detail.getCodCashAmount(), "nothing was collected, so there is no split to show");
    }

    @Test
    @DisplayName("marking a delivery DELIVERED returns the settled status, not the old one")
    void markingDeliveredReturnsTheSettledStatus() {
        // THE ORIGINAL REPORT. The shop marked an order delivered and the
        // screen still read "COD PENDING" - on the response from the very
        // call that had just settled the payment.
        Order order = codOrder();

        OrderDetailResponse afterDelivery =
                orderService.updateOrderStatus(order.getId(), OrderStatus.DELIVERED);

        assertEquals("COD_RECEIVED", afterDelivery.getPaymentStatus(),
                "the response must not carry the status from before its own change");
    }

    @Test
    @DisplayName("an order with no payment row falls back to its own column")
    void anOrderWithoutAPaymentRowKeepsItsOldAnswer() {
        // Orders placed before payment rows were created inside the order
        // transaction have no payment at all. Reading null there would blank
        // out a status the admin screen has always shown.
        Order order = codOrder();
        payments.findByOrderId(order.getId()).ifPresent(payments::delete);

        OrderDetailResponse detail =
                orderService.getOwnedOrderDetail(order.getId(), customer.getId(), true);

        assertEquals("COD_PENDING", detail.getPaymentStatus());
    }
}
