package com.gpstore.intelligence;

import org.springframework.web.bind.annotation.*;

@RestController
public class CommerceIntelligenceController {
    private final CommerceIntelligenceService intelligence;

    public CommerceIntelligenceController(CommerceIntelligenceService intelligence) {
        this.intelligence = intelligence;
    }

    @GetMapping("/api/shop/intelligence")
    public CommerceIntelligenceService.MerchantInsight merchant(
            @RequestParam(defaultValue = "30") int days) {
        return intelligence.merchant(days);
    }

    @GetMapping("/api/platform/marketplace-intelligence")
    public CommerceIntelligenceService.MarketplaceInsight platform(
            @RequestParam(defaultValue = "30") int days) {
        return intelligence.platform(days);
    }
}
