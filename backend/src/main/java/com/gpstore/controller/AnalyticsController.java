package com.gpstore.controller;

import com.gpstore.service.AnalyticsService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

// Admin only across this whole controller (enforced in SecurityConfig).
@RestController
@RequestMapping("/api/analytics")
public class AnalyticsController {

    private final AnalyticsService analyticsService;

    public AnalyticsController(AnalyticsService analyticsService) {
        this.analyticsService = analyticsService;
    }

    @GetMapping("/sales-summary")
    public Map<String, Object> getSalesSummary(@RequestParam(defaultValue = "30") int days) {
        return analyticsService.getSalesSummary(days);
    }

    /**
     * Daily revenue and order count for the dashboard chart. Every day in
     * the window is present, including days with no orders - see
     * AnalyticsService.getSalesSeries for why the gaps are filled here and
     * not on the client.
     */
    @GetMapping("/sales-series")
    public List<Map<String, Object>> getSalesSeries(@RequestParam(defaultValue = "30") int days) {
        return analyticsService.getSalesSeries(days);
    }

    @GetMapping("/order-status-breakdown")
    public Map<String, Long> getOrderStatusBreakdown() {
        return analyticsService.getOrderStatusBreakdown();
    }

    @GetMapping("/top-products")
    public List<Map<String, Object>> getTopProducts(
            @RequestParam(defaultValue = "30") int days,
            @RequestParam(defaultValue = "10") int limit) {
        return analyticsService.getTopProducts(days, Math.min(limit, 50));
    }

    /**
     * How the period's orders split between same-day and next-morning.
     *
     * <p>Real rows from orders.delivery_type - see
     * AnalyticsService.getDeliveryTypeBreakdown for why orders placed before
     * the feature are reported as UNRECORDED rather than guessed at.
     */
    @GetMapping("/delivery-types")
    public List<Map<String, Object>> getDeliveryTypeBreakdown(
            @RequestParam(defaultValue = "30") int days) {
        return analyticsService.getDeliveryTypeBreakdown(days);
    }

    @GetMapping("/low-stock-count")
    public Map<String, Long> getLowStockCount() {
        return Map.of("lowStockCount", analyticsService.getLowStockCount());
    }
}
