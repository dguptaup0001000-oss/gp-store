package com.gpstore.controller;

import com.gpstore.dto.response.ProductResponse;
import com.gpstore.security.CurrentUser;
import com.gpstore.service.RecommendationService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/recommendations")
public class RecommendationController {

    private final RecommendationService recommendationService;
    private final CurrentUser currentUser;

    public RecommendationController(RecommendationService recommendationService, CurrentUser currentUser) {
        this.recommendationService = recommendationService;
        this.currentUser = currentUser;
    }

    // Public - shown on a product page: "customers who bought this also bought...".
    @GetMapping("/frequently-bought-together/{productId}")
    public List<ProductResponse> frequentlyBoughtTogether(
            @PathVariable Long productId,
            @RequestParam(defaultValue = "5") int limit) {
        return recommendationService.frequentlyBoughtWith(productId, Math.min(limit, 20));
    }

    // Public - a homepage "trending near you" style section.
    @GetMapping("/trending")
    public List<ProductResponse> trending(
            @RequestParam(defaultValue = "7") int days,
            @RequestParam(defaultValue = "10") int limit) {
        return recommendationService.trending(days, Math.min(limit, 50));
    }

    // Requires login - reorder suggestions from THIS customer's own history.
    @GetMapping("/for-me")
    public List<ProductResponse> forMe(@RequestParam(defaultValue = "10") int limit) {
        return recommendationService.forCustomer(currentUser.customerId(), Math.min(limit, 50));
    }
}
