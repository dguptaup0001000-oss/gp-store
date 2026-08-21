package com.gpstore.catalog;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.util.List;

/**
 * One row of the generated test catalog, exactly as it appears in
 * resources/catalog/gp-store-test-catalog.json.
 *
 * @JsonIgnoreProperties so that adding a field to the generator does not
 * break a running backend that has not been redeployed yet - the file and
 * the code are versioned separately and will drift.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CatalogSeedRecord(
        String sku,
        String name,
        String brand,
        String category,
        String subcategory,
        Double packQuantity,
        String packUnit,
        BigDecimal mrp,
        BigDecimal sellingPrice,
        Integer discountPercent,
        String description,
        Integer stock,
        Boolean available,
        Boolean active,
        Boolean bestseller,
        Boolean featured,
        List<String> searchKeywords,
        List<String> images,
        Boolean isTestData,
        Boolean priceVerified,
        String dataSource,
        String imageSource) {
}
