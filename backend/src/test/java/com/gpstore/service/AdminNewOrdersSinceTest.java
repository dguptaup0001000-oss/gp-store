package com.gpstore.service;

import com.gpstore.dto.response.AdminNewOrdersSinceResponse;
import com.gpstore.entity.Customer;
import com.gpstore.entity.Order;
import com.gpstore.entity.Payment;
import com.gpstore.enums.OrderStatus;
import com.gpstore.enums.PaymentStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminNewOrdersSinceTest {

    @Test
    void emptyFoundKeepsTheRequestedCursor() {
        AdminNewOrdersSinceResponse response =
                OrderService.composeNewOrdersSince(42L, List.of());
        assertEquals(42L, response.getAfterId());
        assertTrue(response.getOrders().isEmpty());
    }

    @Test
    void returnsNewOrdersOldestFirstAndAdvancesAfterIdToLastReturned() {
        Order first = order(43L, "Ramesh Kumar", "520.00");
        Order second = order(44L, "Priya", "780.50");
        AdminNewOrdersSinceResponse response =
                OrderService.composeNewOrdersSince(42L, List.of(first, second));
        assertEquals(44L, response.getAfterId());
        assertEquals(2, response.getOrders().size());
        assertEquals(43L, response.getOrders().get(0).getOrderId());
        assertEquals("Ramesh Kumar", response.getOrders().get(0).getCustomerName());
        assertEquals("520", response.getOrders().get(0).getOrderAmount());
        assertEquals("780.50", response.getOrders().get(1).getOrderAmount());
    }

    @Test
    void aCappedBurstAdvancesOnlyToTheLastReturnedIdSoTheRestAreNotSkipped() {
        Order twentieth = order(62L, "Asha", "100");
        AdminNewOrdersSinceResponse response =
                OrderService.composeNewOrdersSince(42L, List.of(twentieth));
        assertEquals(62L, response.getAfterId());
    }

    @Test
    void namelessCustomerIsSpokenAsACustomerNotBlank() {
        Order order = order(5L, "  ", "1");
        order.setCustomer(new Customer());
        AdminNewOrdersSinceResponse response =
                OrderService.composeNewOrdersSince(4L, List.of(order));
        assertEquals("a customer", response.getOrders().get(0).getCustomerName());
    }

    @Test
    void unpaidOnlineOrderHoldsTheCursorSoALaterConfirmIsNotSkipped() {
        Order pending = order(43L, "Ramesh Kumar", "520.00");
        pending.setOrderStatus(OrderStatus.PENDING_CONFIRMATION);
        Order paid = order(44L, "Priya", "780.50");
        paid.setOrderStatus(OrderStatus.CONFIRMED);
        AdminNewOrdersSinceResponse response =
                OrderService.composeNewOrdersSince(42L, List.of(pending, paid));
        assertEquals(42L, response.getAfterId());
        assertTrue(response.getOrders().isEmpty());
    }

    @Test
    void confirmedOrdersAnnounceAndUnpaidAfterThemHoldTheCursor() {
        Order paid = order(43L, "Ramesh Kumar", "520.00");
        paid.setOrderStatus(OrderStatus.CONFIRMED);
        Order pending = order(44L, "Priya", "780.50");
        pending.setOrderStatus(OrderStatus.PENDING_CONFIRMATION);
        AdminNewOrdersSinceResponse response =
                OrderService.composeNewOrdersSince(42L, List.of(paid, pending));
        assertEquals(43L, response.getAfterId());
        assertEquals(1, response.getOrders().size());
        assertEquals(43L, response.getOrders().get(0).getOrderId());
    }

    @Test
    void cancelledOrderDoesNotHoldTheCursor() {
        Order cancelled = order(43L, "Ramesh Kumar", "520.00");
        cancelled.setOrderStatus(OrderStatus.CANCELLED);
        Order paid = order(44L, "Priya", "780.50");
        paid.setOrderStatus(OrderStatus.CONFIRMED);
        AdminNewOrdersSinceResponse response =
                OrderService.composeNewOrdersSince(42L, List.of(cancelled, paid));
        assertEquals(44L, response.getAfterId());
        assertEquals(1, response.getOrders().size());
        assertEquals(44L, response.getOrders().get(0).getOrderId());
    }

    @Test
    void unpaidPaymentCannotBeAdminConfirmed() {
        assertFalse(OrderService.paymentAllowsConfirm(null));
        Payment pending = new Payment();
        pending.setPaymentStatus(PaymentStatus.PENDING);
        assertFalse(OrderService.paymentAllowsConfirm(pending));
        Payment success = new Payment();
        success.setPaymentStatus(PaymentStatus.SUCCESS);
        assertTrue(OrderService.paymentAllowsConfirm(success));
        Payment cod = new Payment();
        cod.setPaymentStatus(PaymentStatus.COD_PENDING);
        assertTrue(OrderService.paymentAllowsConfirm(cod));
    }

    private static Order order(long id, String name, String amount) {
        Order order = new Order();
        order.setId(id);
        order.setTotalAmount(new BigDecimal(amount));
        Customer customer = new Customer();
        customer.setFullName(name);
        order.setCustomer(customer);
        return order;
    }
}
