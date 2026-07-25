package com.gpstore.service;

import com.gpstore.entity.Category;
import com.gpstore.entity.Product;
import com.gpstore.entity.ProductVariant;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TaxServiceTest {

    private final TaxService taxService = new TaxService();

    @Test
    void variantOverrideTakesPrecedenceOverCategoryRate() {
        ProductVariant variant = variantWithCategoryRate(new BigDecimal("18"));
        variant.setGstRateOverride(new BigDecimal("5"));

        assertEquals(new BigDecimal("5"), taxService.resolveGstRate(variant));
    }

    @Test
    void fallsBackToCategoryRateWhenNoOverride() {
        ProductVariant variant = variantWithCategoryRate(new BigDecimal("12"));

        assertEquals(new BigDecimal("12"), taxService.resolveGstRate(variant));
    }

    @Test
    void zeroWhenNeitherOverrideNorCategoryRateSet() {
        ProductVariant variant = variantWithCategoryRate(null);

        assertEquals(BigDecimal.ZERO, taxService.resolveGstRate(variant));
    }

    @Test
    void extractsCorrectTaxFromInclusivePrice() {
        // 105 at 5% GST-inclusive contains exactly 5.00 of tax:
        // 105 - (105 / 1.05) = 5.00
        BigDecimal tax = taxService.extractTaxComponent(new BigDecimal("105"), new BigDecimal("5"));
        assertEquals(new BigDecimal("5.00"), tax);
    }

    @Test
    void zeroRateMeansZeroTax() {
        BigDecimal tax = taxService.extractTaxComponent(new BigDecimal("100"), BigDecimal.ZERO);
        assertEquals(BigDecimal.ZERO, tax);
    }

    @Test
    void nullRateTreatedAsZeroTaxNotAnError() {
        BigDecimal tax = taxService.extractTaxComponent(new BigDecimal("100"), null);
        assertEquals(BigDecimal.ZERO, tax);
    }

    private ProductVariant variantWithCategoryRate(BigDecimal categoryGstRate) {
        Category category = new Category();
        category.setGstRate(categoryGstRate);

        Product product = new Product();
        product.setCategory(category);

        ProductVariant variant = new ProductVariant();
        variant.setProduct(product);
        return variant;
    }
}
