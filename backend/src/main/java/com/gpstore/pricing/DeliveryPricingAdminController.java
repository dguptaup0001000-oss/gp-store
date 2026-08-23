package com.gpstore.pricing;

import com.gpstore.entity.DeliveryPricingSettings;
import com.gpstore.entity.Order;
import com.gpstore.exception.ResourceNotFoundException;
import com.gpstore.repository.OrderRepository;
import com.gpstore.security.CurrentUser;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/**
 * Reading and changing the delivery price rules, and seeing how any one order
 * was priced.
 *
 * Admin only - see SecurityConfig. Editing these numbers changes what every
 * future customer pays, which is exactly the sort of lever that needs a door
 * on it.
 */
@RestController
@RequestMapping("/api/admin/delivery-pricing")
public class DeliveryPricingAdminController {

    private final DeliveryPricingService pricingService;
    private final OrderRepository orderRepository;
    private final CurrentUser currentUser;

    public DeliveryPricingAdminController(DeliveryPricingService pricingService,
                                          OrderRepository orderRepository,
                                          CurrentUser currentUser) {
        this.pricingService = pricingService;
        this.orderRepository = orderRepository;
        this.currentUser = currentUser;
    }

    @GetMapping("/settings")
    public DeliveryPricingSettings settings() {
        return pricingService.settings();
    }

    /**
     * Replaces the pricing rules.
     *
     * Anything blank or negative is replaced with the V1 default rather than
     * rejected - see DeliveryPricingSettings.normalise(). A half-filled form
     * must never be able to produce a negative delivery charge on a real
     * customer's checkout, and failing the save would leave the shop unable to
     * fix a number in a hurry.
     */
    @PutMapping("/settings")
    public DeliveryPricingSettings save(@RequestBody DeliveryPricingSettings incoming) {
        return pricingService.save(incoming, "admin:" + currentUser.customerId());
    }

    /**
     * How one order's delivery charge was arrived at - every line §10 asks for.
     *
     * Read from what was STORED on the order, not recomputed. The settings may
     * have been edited since, and a screen that re-ran the arithmetic would
     * show a number this customer was never charged.
     */
    @GetMapping("/orders/{orderId}")
    @Transactional(readOnly = true)
    public Map<String, Object> breakdown(@PathVariable Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found: " + orderId));

        BigDecimal normal = order.getDeliveryNormalCharge();
        BigDecimal profit = order.getDeliveryOrderProfit();
        BigDecimal multiplier = pricingService.settings().getFreeDeliveryMultiplier();

        Map<String, Object> body = new HashMap<>();
        body.put("orderId", order.getId());
        body.put("orderNumber", order.getOrderNumber());
        body.put("orderValue", order.getTotalAmount());
        body.put("availableProfit", profit);
        body.put("distanceKm", order.getDeliveryDistanceKm());
        body.put("totalWeightKg", order.getDeliveryWeightKg());
        body.put("distanceCharge", order.getDeliveryDistanceCharge());
        body.put("weightCharge", order.getDeliveryWeightCharge());
        body.put("normalDeliveryCharge", normal);
        body.put("freeDeliveryRequiredProfit",
                normal == null ? null : normal.multiply(multiplier));
        body.put("freeDelivery", Boolean.TRUE.equals(order.getFreeDeliveryApplied()));
        body.put("subsidy", order.getDeliverySubsidy());
        body.put("finalDeliveryCharge", order.getDeliveryFee());

        // The caveats, if there were any. This is where "no cost price on
        // three items" surfaces to somebody who can fix it, rather than
        // staying in a log that has since rotated away.
        body.put("notes", order.getDeliveryPricingNotes());

        // Orders placed before this pricing existed have no breakdown, and
        // saying so is better than rendering a screen full of nulls that looks
        // like a bug.
        body.put("pricedByCurrentSystem", normal != null);
        return body;
    }
}
