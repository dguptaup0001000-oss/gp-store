package com.gpstore.controller;

import com.gpstore.entity.ProductVariant;
import com.gpstore.service.ProductVariantService;
import com.gpstore.service.VariantImageService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/product-variants")
public class ProductVariantController {

    private final ProductVariantService productVariantService;
    private final VariantImageService variantImageService;

    public ProductVariantController(ProductVariantService productVariantService,
                                    VariantImageService variantImageService) {
        this.productVariantService = productVariantService;
        this.variantImageService = variantImageService;
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

    /**
     * A variant's photos, read.
     *
     * Public, like the rest of the catalogue - these are pictures of
     * groceries on a shelf. Empty for a variant nobody has photographed,
     * which is every variant that existed before this feature and is a
     * perfectly normal answer rather than a not-found.
     */
    @GetMapping("/{id}/images")
    public List<String> getImages(@PathVariable Long id) {
        return variantImageService.imagesFor(id);
    }

    /**
     * A variant's photos, replaced with exactly this list in this order.
     *
     * PUT, not POST, and the whole list rather than one image, because the
     * first entry is the primary photo - so order is meaning, and order is
     * what add/remove/reorder endpoints get wrong between three round trips.
     * Sending the list you want is idempotent: re-sending it after a dropped
     * response changes nothing.
     *
     * Admin only (enforced in SecurityConfig, like every other write on this
     * controller). The five-photo limit is checked here and again by a
     * database trigger - see V22 for why both.
     */
    @PutMapping("/{id}/images")
    public List<String> replaceImages(@PathVariable Long id,
                                      @RequestBody VariantImagesRequest request) {
        return variantImageService.replaceImages(id, request.imageUrls());
    }

    /** The complete ordered list of photo URLs a variant should have. */
    public record VariantImagesRequest(List<String> imageUrls) {
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
