package com.gpstore.dto.response;

import java.io.Serializable;

/**
 * Deliberately has no logoUrl/bannerUrl field - there is no Brand entity in
 * this system, "brand" is just a plain text field on Product. Adding fake
 * image fields here would be pretending data exists that doesn't. The
 * Flutter app renders an initials-based avatar instead of a real logo.
 *
 * Serializable - see ProductResponse's doc comment. getBrandsWithCounts() is
 * @Cacheable too.
 */
public class BrandSummary implements Serializable {

    private final String brand;
    private final long productCount;

    public BrandSummary(String brand, long productCount) {
        this.brand = brand;
        this.productCount = productCount;
    }

    public String getBrand() { return brand; }
    public long getProductCount() { return productCount; }
}
