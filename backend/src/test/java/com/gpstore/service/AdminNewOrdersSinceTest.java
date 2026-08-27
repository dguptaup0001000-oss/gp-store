package com.gpstore.service;

import com.gpstore.dto.response.AdminNewOrdersSinceResponse;
import com.gpstore.entity.Customer;
import com.gpstore.entity.Order;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
