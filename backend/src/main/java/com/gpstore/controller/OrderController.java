package com.gpstore.controller;

import com.gpstore.security.AdminPermission;
import com.gpstore.enums.OrderStatus;

import com.gpstore.dto.OrderResponse;
import com.gpstore.dto.request.PlaceOrderRequest;
import com.gpstore.dto.response.PlaceOrderResponse;
import com.gpstore.security.CurrentUser;
import com.gpstore.service.OrderService;

import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private static final Logger log = LoggerFactory.getLogger(OrderController.class);

    private final OrderService orderService;
    private final CurrentUser currentUser;

    public OrderController(OrderService orderService, CurrentUser currentUser) {
        this.orderService = orderService;
        this.currentUser = currentUser;
    }

    // Place a new order for the logged-in customer.
    //
    // Idempotency-Key is required by default (orders.require-idempotency-key).
    // The client generates one UUID per checkout attempt and re-sends that
    // same value on any retry (network timeout or a double-tap on Place
    // Order). A retried request returns the original order instead of
    // creating a second one. The flag exists only so one deploy can accept
    // an older APK; leaving it off removes retry protection.
    // Timed at INFO - this is the first of two sequential calls checkout
    // makes right after the "Place Order" countdown, and how long it takes
    // is exactly what determines how long the customer stares at a spinner.
    @PostMapping("/place")
    public PlaceOrderResponse placeOrder(
            @Valid @RequestBody PlaceOrderRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        long start = System.currentTimeMillis();
        PlaceOrderResponse response = orderService.placeOrder(request, currentUser.customerId(), idempotencyKey);
        log.info("POST /api/orders/place took {} ms (orderNumber={})", System.currentTimeMillis() - start, response.getOrderNumber());
        return response;
    }

    // Read-only preview of what this order would cost - shows delivery fee
    // and discount BEFORE the customer commits, not just after placing it.
    @GetMapping("/checkout-preview")
    public com.gpstore.dto.response.CheckoutPreviewResponse previewCheckout(
            @RequestParam Long addressId,
            @RequestParam(required = false) String couponCode) {
        return orderService.previewCheckout(currentUser.customerId(), addressId, couponCode);
    }

    // Admin only (enforced in SecurityConfig): a specific customer's order
    // history, newest first - for support/dispute lookups.
    @GetMapping("/customer/{customerId}")
    public Page<OrderResponse> getCustomerOrders(
            @PathVariable Long customerId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, Math.min(size, 100));
        return orderService.getCustomerOrdersForAdmin(customerId, pageable);
    }

    // Order history for the logged-in customer only.
    @GetMapping("/my-orders")
    public Page<OrderResponse> getMyOrders(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, Math.min(size, 100));
        return orderService.getMyOrders(currentUser.customerId(), pageable);
    }

    // Admin only (enforced in SecurityConfig): every order in the system, WITH customer name.
    @GetMapping("/admin/all")
    public Page<OrderResponse> getAllOrdersForAdmin(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, Math.min(size, 100));
        return orderService.getAllOrdersForAdmin(pageable);
    }

    // Admin only. Shop-counter soundbox: first call (no afterId) returns the
    // current high-water mark and no orders, so historical orders are not
    // announced. Later calls return new orders oldest-first.
    @GetMapping("/admin/since")
    public com.gpstore.dto.response.AdminNewOrdersSinceResponse getNewOrdersSince(
            @RequestParam(required = false) Long afterId) {
        return orderService.getNewOrdersSince(afterId);
    }

    // Full detail for one of the caller's own orders - real items, address,
    // and delivery tracking. Ownership is verified server-side, never
    // trusted from the ID alone. Admins can view ANY order (isAdmin bypasses
    // the ownership check) - they need this to actually manage/fulfill orders.
    @GetMapping("/{orderId}")
    public com.gpstore.dto.response.OrderDetailResponse getOrderDetail(@PathVariable Long orderId) {
        boolean isAdmin = currentUser.has(AdminPermission.ORDERS_VIEW);
        return orderService.getOwnedOrderDetail(orderId, currentUser.customerId(), isAdmin);
    }

    // Admin only (enforced in SecurityConfig).
    @PutMapping("/{orderId}/status")
    public com.gpstore.dto.response.OrderDetailResponse updateOrderStatus(@PathVariable Long orderId, @RequestParam OrderStatus status) {
        return orderService.updateOrderStatus(orderId, status);
    }

    // Customers may cancel only their own order; admins may cancel any order.
    @PutMapping("/{orderId}/cancel")
    public com.gpstore.dto.response.OrderDetailResponse cancelOrder(@PathVariable Long orderId) {
        boolean isAdmin = currentUser.has(AdminPermission.ORDERS_MANAGE);
        return orderService.cancelOrder(orderId, currentUser.customerId(), isAdmin);
    }
}
