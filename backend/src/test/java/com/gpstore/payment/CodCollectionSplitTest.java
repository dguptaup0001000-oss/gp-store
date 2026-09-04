package com.gpstore.payment;

import com.gpstore.entity.Customer;
import com.gpstore.entity.Order;
import com.gpstore.entity.Payment;
import com.gpstore.entity.Role;
import com.gpstore.enums.OrderStatus;
import com.gpstore.enums.PaymentMethod;
import com.gpstore.enums.PaymentStatus;
import com.gpstore.exception.BadRequestException;
import com.gpstore.repository.CustomerRepository;
import com.gpstore.repository.OrderRepository;
import com.gpstore.repository.PaymentRepository;
import com.gpstore.service.PaymentService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Recording how cash-on-delivery money actually arrived at the door.
 *
 * WHY THIS EXISTS. A delivered order was still showing "COD PENDING" on the
 * admin screen, because two different paths reach DELIVERED and only one of
 * them settled the payment: the worker's delivery flow does, the admin's
 * order-status dropdown does not. There was also no way for a rider to say "I
 * took the money" - the endpoint existed but no app called it, and it recorded
 * nothing about HOW the money came in.
 *
 * A customer at the door may hand over part in notes and scan the shop's QR
 * for the rest, so this records two amounts rather than one method. The rider
 * says how the money was made up; the SERVER says how much is owed. A tampered
 * app can misreport the split but cannot settle an order for less than it
 * costs, which is the only part that would lose the shop money.
 */
@SpringBootTest(properties = {
        "outbox.initial-delay-ms=3600000",
        "outbox.drain-interval-ms=3600000",
        "payment.expiry-initial-delay-ms=3600000",
        "idempotency.cleanup-initial-delay-ms=3600000",
        "otp.cleanup-initial-delay-ms=3600000",
        "delivery.late-flag-initial-delay-ms=3600000"
})
@DisplayName("Cash collected at the door, split by how it arrived")
class CodCollectionSplitTest {

    @Autowired private PaymentService paymentService;
    @Autowired private CustomerRepository customers;
    @Autowired private OrderRepository orders;
    @Autowired private PaymentRepository payments;

    private static final BigDecimal DUE = new BigDecimal("2563.00");

    // isAdmin=true throughout: WHO MAY SETTLE is a separate question, already
    // covered by the worker-ownership tests ("a worker cannot mark COD
    // collected on someone else's order"). These tests are about the split
    // itself, and running them as a rider would mean building a whole roster
    // and delivery assignment to test arithmetic. The partner id is still
    // passed and asserted, so "who took the money" stays pinned.

    private Order codOrder() {
        Customer c = new Customer();
        c.setFullName("A Customer");
        c.setEmail("cod-" + System.nanoTime() + "@example.com");
        c.setMobileNumber("9" + String.format("%09d", System.nanoTime() % 1_000_000_000L));
        c.setPassword("x");
        c.setRole(Role.CUSTOMER);
        c.setActive(true);
        c.setEnabled(true);
        c = customers.save(c);

        Order o = new Order();
        o.setOrderNumber("COD-" + System.nanoTime());
        o.setCustomer(c);
        o.setTotalAmount(DUE);
        o.setOrderStatus(OrderStatus.OUT_FOR_DELIVERY);
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
    @DisplayName("part cash, part QR - both are recorded")
    void aSplitIsRecordedAsTwoAmounts() {
        Order order = codOrder();

        paymentService.completeCodPayment(order.getId(), 7L, true,
                new BigDecimal("1500.00"), new BigDecimal("1063.00"));

        Payment after = payments.findByOrderId(order.getId()).orElseThrow();
        assertEquals(PaymentStatus.COD_RECEIVED, after.getPaymentStatus());
        assertEquals(0, new BigDecimal("1500.00").compareTo(after.getCodCashAmount()));
        assertEquals(0, new BigDecimal("1063.00").compareTo(after.getCodUpiAmount()));
        assertEquals(7L, after.getCodCollectedByPartnerId(),
                "the shop cannot ask who took the money");
        assertNotNull(after.getCodCollectedAt());
    }

    @Test
    @DisplayName("all of it by QR is a split too - cash is zero, not null")
    void allUpiRecordsZeroCash() {
        Order order = codOrder();

        paymentService.completeCodPayment(order.getId(), 7L, true, BigDecimal.ZERO, DUE);

        Payment after = payments.findByOrderId(order.getId()).orElseThrow();
        assertEquals(0, BigDecimal.ZERO.compareTo(after.getCodCashAmount()));
        assertEquals(0, DUE.compareTo(after.getCodUpiAmount()));
    }

    @Test
    @DisplayName("a split that does not add up to the amount due is refused")
    void aSplitMustReconcile() {
        Order order = codOrder();

        // THE ONE THAT LOSES MONEY. A short split looks like a precise record
        // and would put a wrong number straight into the day's cash count.
        BadRequestException ex = assertThrows(BadRequestException.class,
                () -> paymentService.completeCodPayment(order.getId(), 7L, true,
                        new BigDecimal("100.00"), new BigDecimal("100.00")));
        assertTrue(ex.getMessage().contains("add up"), ex.getMessage());

        Payment after = payments.findByOrderId(order.getId()).orElseThrow();
        assertEquals(PaymentStatus.COD_PENDING, after.getPaymentStatus(),
                "a refused split must leave the order unpaid, not half-settled");
    }

    @Test
    @DisplayName("a negative amount is refused")
    void negativeAmountsAreRefused() {
        Order order = codOrder();

        assertThrows(BadRequestException.class,
                () -> paymentService.completeCodPayment(order.getId(), 7L, true,
                        new BigDecimal("-100.00"), new BigDecimal("2663.00")));
    }

    @Test
    @DisplayName("settling without a split still works, and records nothing rather than zero")
    void theAutomaticPathStillSettles() {
        Order order = codOrder();

        // THE PATH THAT MUST NOT BREAK. Marking a delivery delivered settles
        // COD with no split. A rider who forgets the button must not leave the
        // shop's books showing money outstanding on a delivered order.
        paymentService.completeCodPayment(order.getId());

        Payment after = payments.findByOrderId(order.getId()).orElseThrow();
        assertEquals(PaymentStatus.COD_RECEIVED, after.getPaymentStatus());
        assertNull(after.getCodCashAmount(),
                "not recorded must stay null - a shopkeeper reading 0 would think no cash came in");
        assertNull(after.getCodUpiAmount());
    }
}
