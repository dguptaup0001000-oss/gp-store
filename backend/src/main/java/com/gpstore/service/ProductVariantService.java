package com.gpstore.service;

import com.gpstore.entity.ProductVariant;
import com.gpstore.exception.BadRequestException;
import com.gpstore.exception.ResourceNotFoundException;
import com.gpstore.repository.ProductVariantRepository;
import com.gpstore.upload.CatalogImageCleanup;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

@Service
public class ProductVariantService {

    private final ProductVariantRepository productVariantRepository;
    private final CatalogImageCleanup imageCleanup;

    public ProductVariantService(ProductVariantRepository productVariantRepository,
                                 CatalogImageCleanup imageCleanup) {
        this.productVariantRepository = productVariantRepository;
        this.imageCleanup = imageCleanup;
    }

    // Evicts the "products" cache too - Product's response includes its
    // variants (price, availability), so a stale product listing would show
    // old prices after this change otherwise.
    @org.springframework.cache.annotation.CacheEvict(value = "products", allEntries = true)
    public ProductVariant saveProductVariant(ProductVariant productVariant, boolean allowBelowCost) {
        validatePrices(productVariant, allowBelowCost);
        applyImageUrl(productVariant, productVariant.getImageUrl());
        return productVariantRepository.save(productVariant);
    }

    // Unused by the current frontend (confirmed - product creation goes
    // through POST, not this) and admin-only, but was a plain findAll() -
    // every variant of every product ever created. Capped defensively
    // rather than left as live unbounded API surface.
    private static final int ADMIN_LIST_CAP = 500;

    public List<ProductVariant> getAllProductVariants() {
        return productVariantRepository.findAll(org.springframework.data.domain.PageRequest.of(0, ADMIN_LIST_CAP)).getContent();
    }

    public ProductVariant getById(Long id) {
        return productVariantRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product variant not found"));
    }

    /**
     * The endpoint that actually closes the "we have no costPrice on existing
     * products" gap - lets an admin go back and backfill cost/selling price
     * (or any other field) on variants that already exist, rather than only
     * being able to set costPrice at creation time.
     */
    @org.springframework.cache.annotation.CacheEvict(value = "products", allEntries = true)
    public ProductVariant update(Long id, ProductVariant updated, boolean allowBelowCost) {
        ProductVariant existing = getById(id);
        String previousImage = existing.getImageUrl();

        existing.setQuantity(updated.getQuantity());
        existing.setUnit(updated.getUnit());
        existing.setBarcode(updated.getBarcode());
        existing.setSku(updated.getSku());
        applyImageUrl(existing, updated.getImageUrl());
        existing.setAvailable(updated.getAvailable());
        existing.setMrp(updated.getMrp());
        existing.setCostPrice(updated.getCostPrice());
        existing.setDisplayOrder(updated.getDisplayOrder());
        existing.setSellingPrice(updated.getSellingPrice());
        existing.setGstRateOverride(updated.getGstRateOverride());
        existing.setActive(updated.getActive());

        validatePrices(existing, allowBelowCost);

        ProductVariant saved = productVariantRepository.save(existing);
        imageCleanup.deleteReplacedAfterCommit(previousImage, saved.getImageUrl());
        return saved;
    }

    private static void applyImageUrl(ProductVariant variant, String imageUrl) {
        com.gpstore.catalog.CatalogUrlValidator.requireAllowedImageUrlOrEmpty(imageUrl);
        variant.setImageUrl(com.gpstore.catalog.CatalogUrlValidator.trimToNull(imageUrl));
    }

    private void validatePrices(ProductVariant variant, boolean allowBelowCost) {
        if (variant.getSellingPrice() == null
                || variant.getSellingPrice().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BadRequestException("Selling price must be greater than 0");
        }

        if (variant.getCostPrice() != null && variant.getCostPrice().compareTo(BigDecimal.ZERO) < 0) {
            throw new BadRequestException("Cost price cannot be negative");
        }

        if (!allowBelowCost && variant.getSellingPrice() != null && variant.getCostPrice() != null
                && variant.getSellingPrice().compareTo(variant.getCostPrice()) < 0) {
            // Not blocked forever - loss-leader pricing is a legitimate business
            // choice - but it's exactly the kind of mistake a typo produces, so
            // it requires the caller to explicitly pass allowBelowCost=true
            // rather than silently going through.
            throw new BadRequestException(
                    "Selling price is below cost price. If this is intentional (e.g. a loss-leader item), " +
                            "resubmit with allowBelowCost=true.");
        }
    }
}