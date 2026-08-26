package com.gpstore.controller;

import com.gpstore.security.CurrentUser;
import com.gpstore.service.DeliveryService;

import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/deliveries")
public class DeliveryController {

    private final DeliveryService deliveryService;
    private final CurrentUser currentUser;

    public DeliveryController(DeliveryService deliveryService, CurrentUser currentUser) {
        this.deliveryService = deliveryService;
        this.currentUser = currentUser;
    }

    // Assigns an order to a delivery partner's batch (auto-opens a new batch if
    // theirs already has 20 orders) and computes a distance-based ETA.
    // Admin/delivery-role only (enforced in SecurityConfig).
    @PostMapping("/assign")
    public com.gpstore.dto.response.DeliveryResponse assignDelivery(
            @RequestParam Long orderId,
            @RequestParam Long deliveryPartnerId) {

        return deliveryService.assignDelivery(orderId, deliveryPartnerId);
    }

    // The real day-to-day path with 10 partners: automatically picks whichever
    // available partner currently has the lightest load - no admin needs to
    // manually choose one of 10 people for every single order.
    @PostMapping("/auto-assign")
    public com.gpstore.dto.response.DeliveryResponse autoAssignDelivery(@RequestParam Long orderId) {
        return deliveryService.autoAssignDelivery(orderId);
    }

    // Lets you decide "this order needs a pickup, not a bike" yourself, for
    // orders where you know better than the automatic item-count threshold -
    // the system still auto-picks whichever available partner of that vehicle
    // type has the lightest load, so you're not manually choosing a person.
    @PostMapping("/assign-vehicle")
    public com.gpstore.dto.response.DeliveryResponse assignWithVehicleType(
            @RequestParam Long orderId,
            @RequestParam String vehicleType) {
        return deliveryService.assignWithVehicleType(orderId, vehicleType);
    }

    @GetMapping
    public List<com.gpstore.dto.response.DeliveryResponse> getAllDeliveries() {
        return deliveryService.getAllDeliveries();
    }

    @GetMapping("/{id}")
    public Optional<com.gpstore.dto.response.DeliveryResponse> getDeliveryById(@PathVariable Long id) {
        boolean isAdmin = "ADMIN".equals(currentUser.get().getRole());
        return deliveryService.getDeliveryById(id, currentUser.customerId(), isAdmin);
    }

    @GetMapping("/order/{orderId}")
    public Optional<com.gpstore.dto.response.DeliveryResponse> getDeliveryByOrderId(@PathVariable Long orderId) {
        boolean isAdmin = "ADMIN".equals(currentUser.get().getRole());
        return deliveryService.getDeliveryByOrderId(orderId, currentUser.customerId(), isAdmin);
    }

    // Lets a customer check their own order's delivery status/ETA.
    // Ownership is enforced here - never trust customerId from a client.
    @GetMapping("/my-order/{orderId}")
    public Optional<com.gpstore.dto.response.DeliveryResponse> getMyOrderDelivery(@PathVariable Long orderId) {
        return deliveryService.getOwnedDeliveryResponse(orderId, currentUser.customerId());
    }

    // Live tracking screen data for a customer's own order - assigned
    // partner's current GPS position + ETA. Ownership enforced the same
    // way as getMyOrderDelivery() above.
    @GetMapping("/my-order/{orderId}/tracking")
    public com.gpstore.dto.response.DeliveryTrackingResponse getMyOrderTracking(@PathVariable Long orderId) {
        return deliveryService.getMyOrderTracking(orderId, currentUser.customerId());
    }

    @PutMapping("/{id}/status")
    public com.gpstore.dto.response.DeliveryResponse updateDeliveryStatus(
            @PathVariable Long id,
            @RequestParam String status) {

        boolean isAdmin = "ADMIN".equals(currentUser.get().getRole());
        return deliveryService.updateDeliveryStatus(id, status, currentUser.customerId(), isAdmin);
    }

    // A delivery partner's own active assignments - resolved from their
    // logged-in account, never a client-supplied partner id.
    @GetMapping("/my-assignments")
    public List<com.gpstore.dto.response.MyDeliveryResponse> getMyAssignments() {
        return deliveryService.getMyAssignments(currentUser.customerId());
    }

    // The manual-review list for your delivery guarantee - every delivery
    // that has ever missed its promised ETA. No auto-refund is attached to
    // this by design; it's purely a signal for you to act on.
    @GetMapping("/breached")
    public List<com.gpstore.dto.response.DeliveryResponse> getBreachedDeliveries() {
        return deliveryService.getBreachedDeliveries();
    }
}
