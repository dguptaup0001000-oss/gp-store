package com.gpstore.controller;

import com.gpstore.enums.OrderStatus;

import com.gpstore.dto.OrderResponse;
import com.gpstore.dto.request.PlaceOrderRequest;
import com.gpstore.dto.response.PlaceOrderResponse;
import com.gpstore.entity.Order;
import com.gpstore.security.CurrentUser;
import com.gpstore.service.OrderService;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;
    private final CurrentUser currentUser;

    public OrderController(OrderService orderService, CurrentUser currentUser) {
        this.orderService = orderService;
        this.currentUser = currentUser;
    }

    // Place a new order for the logged-in customer.
    @PostMapping("/place")
    public PlaceOrderResponse placeOrder(@Valid @RequestBody PlaceOrderRequest request) {
        return orderService.placeOrder(request, currentUser.customerId());
    }

    // Admin only (enforced in SecurityConfig): every order in the system.
    @GetMapping
    public List<Order> getAllOrders() {
        return orderService.getAll();
    }

    // Admin only (enforced in SecurityConfig): raw orders for an arbitrary customer.
    @GetMapping("/customer/{customerId}")
    public List<Order> getCustomerOrders(@PathVariable Long customerId) {
        return orderService.getCustomerOrders(customerId);
    }

    // Order history for the logged-in customer only.
    @GetMapping("/my-orders")
    public List<OrderResponse> getMyOrders() {
        return orderService.getMyOrders(currentUser.customerId());
    }

    // Admin only (enforced in SecurityConfig).
    @PutMapping("/{orderId}/status")
    public Order updateOrderStatus(@PathVariable Long orderId, @RequestParam OrderStatus status) {
        return orderService.updateOrderStatus(orderId, status);
    }

    // Customers may cancel only their own order; admins may cancel any order.
    @PutMapping("/{orderId}/cancel")
    public Order cancelOrder(@PathVariable Long orderId) {
        boolean isAdmin = "ADMIN".equals(currentUser.get().getRole());
        return orderService.cancelOrder(orderId, currentUser.customerId(), isAdmin);
    }
}
