package com.gpstore.controller;

import com.gpstore.entity.ProductVariant;
import com.gpstore.service.ProductVariantService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/product-variants")
public class ProductVariantController {

    private final ProductVariantService productVariantService;

    public ProductVariantController(ProductVariantService productVariantService) {
        this.productVariantService = productVariantService;
    }

    // Admin only (enforced in SecurityConfig).
    @PostMapping
    public ProductVariant createProductVariant(
            @RequestBody ProductVariant productVariant,
            @RequestParam(defaultValue = "false") boolean allowBelowCost) {
        return productVariantService.saveProductVariant(productVariant, allowBelowCost);
    }

    @GetMapping
    public List<ProductVariant> getAllProductVariants() {
        return productVariantService.getAllProductVariants();
    }

    @GetMapping("/{id}")
    public ProductVariant getById(@PathVariable Long id) {
        return productVariantService.getById(id);
    }

    // The endpoint that actually lets you go back and set costPrice on products
    // that already exist - this didn't exist before (only creation did).
    // Admin only (enforced in SecurityConfig).
    @PutMapping("/{id}")
    public ProductVariant update(
            @PathVariable Long id,
            @RequestBody ProductVariant productVariant,
            @RequestParam(defaultValue = "false") boolean allowBelowCost) {
        return productVariantService.update(id, productVariant, allowBelowCost);
    }
}
