package com.gpstore.controller;

import com.gpstore.security.AdminPermission;
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
        boolean isAdmin = currentUser.has(AdminPermission.DELIVERY_VIEW);
        return deliveryService.getDeliveryById(id, currentUser.get().getWorkerId(), isAdmin);
    }

    @GetMapping("/order/{orderId}")
    public Optional<com.gpstore.dto.response.DeliveryResponse> getDeliveryByOrderId(@PathVariable Long orderId) {
        boolean isAdmin = currentUser.has(AdminPermission.DELIVERY_VIEW);
        return deliveryService.getDeliveryByOrderId(orderId, currentUser.get().getWorkerId(), isAdmin);
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

        // DELIVERY_MANAGE, not DELIVERY_VIEW: this writes. A read role
        // reaching here falls to the worker branch and is refused, which is
        // what SecurityConfig intends for every other delivery write.
        boolean isAdmin = currentUser.has(AdminPermission.DELIVERY_MANAGE);
        // A worker session carries the roster id; an admin carries none and
        // does not need one, because isAdmin skips the ownership lookup.
        return deliveryService.updateDeliveryStatus(
                id, status, currentUser.get().getWorkerId(), isAdmin);
    }

    /**
     * A delivery partner's own active assignments - resolved from their
     * logged-in account, never a client-supplied partner id.
     *
     * THE ROSTER ID, NOT THE CUSTOMER ID. This read customerId(), and the two
     * are different numbers from different tables: a worker session carries a
     * delivery_partners id and no customer id at all, so the only caller this
     * endpoint exists for got "Sign in with a worker login to use the worker
     * app." every time. Meanwhile a staff account with DELIVERY_VIEW - which
     * SecurityConfig also admits here - passed its CUSTOMER id into a lookup
     * that treats it as a roster primary key, and was shown whichever rider
     * happened to hold that number as "my assignments".
     *
     * updateDeliveryStatus above already resolved this correctly; the two
     * simply disagreed. Found by signing a real worker in against a running
     * backend from the worker app's own HTTP client.
     */
    @GetMapping("/my-assignments")
    public List<com.gpstore.dto.response.MyDeliveryResponse> getMyAssignments() {
        return deliveryService.getMyAssignments(currentUser.get().getWorkerId());
    }

    // The manual-review list for your delivery guarantee - every delivery
    // that has ever missed its promised ETA. No auto-refund is attached to
    // this by design; it's purely a signal for you to act on.
    @GetMapping("/breached")
    public List<com.gpstore.dto.response.DeliveryResponse> getBreachedDeliveries() {
        return deliveryService.getBreachedDeliveries();
    }
}
