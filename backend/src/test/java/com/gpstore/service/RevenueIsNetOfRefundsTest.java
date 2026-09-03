package com.gpstore.service;

import com.gpstore.entity.*;
import com.gpstore.enums.*;
import com.gpstore.payment.gateway.PaymentGateway;
import com.gpstore.payment.gateway.PaymentGateway.GatewayRefund;
import com.gpstore.repository.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/**
 * The dashboard has to stop reporting money the shop gave back.
 *
 * WHY THIS BECAME WRONG RATHER THAN ALWAYS BEING WRONG. sumRevenueBetween
 * excludes cancelled orders and subtracts nothing for refunds. While refunds
 * barely worked - the money never left the building - gross and net agreed
 * and nobody could tell. They do not agree any more: a shop can refund part
 * of an order, refund it again later, or take goods back through a return.
 * A dashboard reporting 500 for an order it refunded 300 of tells a
 * shopkeeper they earned money they handed over.
 *
 * ASSERTED AS DELTAS, not absolutes. getSalesSummary aggregates every order
 * in the window, and this suite shares a database with the rest - so the only
 * honest question is what THIS order and THIS refund changed.
 */
@SpringBootTest(properties = {
        "cashfree.webhook-secret=net-revenue-test-secret",
        "refund.reconcile-initial-delay-ms=3600000",
        "refund.reconcile-interval-ms=3600000",
        "outbox.initial-delay-ms=3600000",
        "outbox.drain-interval-ms=3600000",
        "payment.expiry-initial-delay-ms=3600000",
        "payment.expiry-interval-ms=3600000"
})
@DisplayName("Revenue is net of refunds")
class RevenueIsNetOfRefundsTest {

    private static final BigDecimal PAID = new BigDecimal("500.00");

    @Autowired private AnalyticsService analytics;
    @Autowired private PaymentService paymentService;
    @Autowired private OrderRepository orderRepository;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private CustomerRepository customerRepository;

    @MockitoSpyBean private PaymentGateway gateway;

    @Test
    @DisplayName("a refund reduces net revenue and leaves gross alone")
    void aRefundReducesNetRevenue() {
        Map<String, Object> before = analytics.getSalesSummary(7);

        Order order = deliveredOrder();
        Payment payment = onlinePayment(order);
        stubRefund(GatewayRefund.State.SUCCEEDED);

        paymentService.refundPayment(order.getId(), new BigDecimal("200.00"));

        Map<String, Object> after = analytics.getSalesSummary(7);

        assertEquals(0, PAID.compareTo(delta(before, after, "revenue")),
                "Gross revenue is what was sold, and 500 was sold.");
        assertEquals(0, new BigDecimal("200.00").compareTo(delta(before, after, "refunded")),
                "200 went back.");
        assertEquals(0, new BigDecimal("300.00").compareTo(delta(before, after, "netRevenue")),
                "The shop kept 300. Before this change the dashboard said 500 - "
                        + "money the shopkeeper had already handed over.");

        assertNotNull(payment.getId());
    }

    @Test
    @DisplayName("a refund still in flight has not left the bank yet")
    void aPendingRefundIsNotSubtracted() {
        Map<String, Object> before = analytics.getSalesSummary(7);

        Order order = deliveredOrder();
        onlinePayment(order);
        stubRefund(GatewayRefund.State.PENDING);

        paymentService.refundPayment(order.getId(), new BigDecimal("200.00"));

        Map<String, Object> after = analytics.getSalesSummary(7);

        // Counting a refund the moment it is requested would understate what
        // the shop holds - and a refund the provider later refuses would have
        // been subtracted for good.
        assertEquals(0, BigDecimal.ZERO.compareTo(delta(before, after, "refunded")));
        assertEquals(0, PAID.compareTo(delta(before, after, "netRevenue")));
    }

    @Test
    @DisplayName("a cancelled order's refund cannot drive the total negative")
    void aCancelledOrdersRefundIsExcluded() {
        Map<String, Object> before = analytics.getSalesSummary(7);

        // A cancelled order contributes NO revenue, so subtracting its refund
        // would take money out of a total it never put money into. A week
        // whose only activity was a cancellation-and-refund would report
        // negative takings.
        Order order = deliveredOrder();
        Payment payment = onlinePayment(order);
        stubRefund(GatewayRefund.State.SUCCEEDED);
        paymentService.refundPayment(order.getId(), PAID);

        order.setOrderStatus(OrderStatus.CANCELLED);
        orderRepository.save(order);

        Map<String, Object> after = analytics.getSalesSummary(7);

        assertEquals(0, BigDecimal.ZERO.compareTo(delta(before, after, "revenue")));
        assertEquals(0, BigDecimal.ZERO.compareTo(delta(before, after, "refunded")));
        assertEquals(0, BigDecimal.ZERO.compareTo(delta(before, after, "netRevenue")));

        assertNotNull(payment.getId());
    }

    @Test
    @DisplayName("the average basket stays gross, because it answers a different question")
    void averageOrderValueIsUnaffected() {
        Order order = deliveredOrder();
        onlinePayment(order);
        stubRefund(GatewayRefund.State.SUCCEEDED);
        paymentService.refundPayment(order.getId(), new BigDecimal("200.00"));

        Map<String, Object> summary = analytics.getSalesSummary(7);

        BigDecimal revenue = money(summary.get("revenue"));
        long orderCount = ((Number) summary.get("orderCount")).longValue();
        BigDecimal expected = orderCount == 0
                ? BigDecimal.ZERO
                : revenue.divide(BigDecimal.valueOf(orderCount), 2, java.math.RoundingMode.HALF_UP);

        // "How big is a typical basket" is about what customers put in it.
        // Dividing net revenue by order count would quietly rename it
        // "average kept per order" while leaving the label alone.
        assertEquals(0, expected.compareTo(money(summary.get("averageOrderValue"))));
    }

    @Test
    @DisplayName("the summary still carries every key it did before")
    void theOriginalKeysSurvive() {
        Map<String, Object> summary = analytics.getSalesSummary(7);

        for (String key : new String[]{
                "periodDays", "revenue", "orderCount", "cancelledCount", "averageOrderValue",
                "previousRevenue", "previousOrderCount", "revenueChangePercent",
                "orderCountChangePercent"}) {
            assertTrue(summary.containsKey(key), "dropped the existing key " + key);
        }
        for (String key : new String[]{
                "refunded", "netRevenue", "previousRefunded", "previousNetRevenue",
                "netRevenueChangePercent"}) {
            assertTrue(summary.containsKey(key), "missing the new key " + key);
        }
    }

    // ------------------------------------------------------------- fixtures

    private static BigDecimal money(Object value) {
        if (value == null) return BigDecimal.ZERO;
        if (value instanceof BigDecimal decimal) return decimal;
        return new BigDecimal(value.toString());
    }

    private static BigDecimal delta(Map<String, Object> before, Map<String, Object> after, String key) {
        return money(after.get(key)).subtract(money(before.get(key)));
    }

    private void stubRefund(GatewayRefund.State state) {
        doAnswer(call -> {
            PaymentGateway.GatewayRefundRequest asked = call.getArgument(0);
            return new GatewayRefund(asked.refundId(), "cf_net_" + System.nanoTime(),
                    state, asked.amount(), null);
        }).when(gateway).requestRefund(any());
    }

    private Order deliveredOrder() {
        Customer customer = new Customer();
        customer.setFullName("Net Revenue Customer");
        customer.setEmail("net-" + System.nanoTime() + "@example.com");
        customer.setMobileNumber("9" + String.format("%09d", System.nanoTime() % 1_000_000_000L));
        customer.setPassword("irrelevant");
        customer.setEnabled(true);
        customer.setActive(true);
        customer.setRole(Role.CUSTOMER);
        customer = customerRepository.save(customer);

        Order order = new Order();
        order.setOrderNumber("NET-" + System.nanoTime());
        order.setCustomer(customer);
        order.setTotalAmount(PAID);
        order.setOrderStatus(OrderStatus.DELIVERED);
        // Inside the 7-day window the assertions ask about.
        order.setOrderDate(LocalDateTime.now().minusHours(1));
        order.setActive(true);
        return orderRepository.save(order);
    }

    private Payment onlinePayment(Order order) {
        Payment payment = new Payment();
        payment.setOrder(order);
        payment.setPaymentMethod(PaymentMethod.ONLINE);
        payment.setPaymentStatus(PaymentStatus.SUCCESS);
        payment.setProvider(PaymentProvider.CASHFREE);
        payment.setProviderOrderId("GP-" + order.getId() + "-net");
        payment.setAmount(PAID);
        payment.setCurrency("INR");
        payment.setActive(true);
        return paymentRepository.save(payment);
    }
}
