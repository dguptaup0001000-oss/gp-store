package com.gpstore.dto.response;

/**
 * Deliberately has no logoUrl/bannerUrl field - there is no Brand entity in
 * this system, "brand" is just a plain text field on Product. Adding fake
 * image fields here would be pretending data exists that doesn't. The
 * Flutter app renders an initials-based avatar instead of a real logo.
 */
public class BrandSummary {

    private final String brand;
    private final long productCount;

    public BrandSummary(String brand, long productCount) {
        this.brand = brand;
        this.productCount = productCount;
    }

    public String getBrand() { return brand; }
    public long getProductCount() { return productCount; }
}
