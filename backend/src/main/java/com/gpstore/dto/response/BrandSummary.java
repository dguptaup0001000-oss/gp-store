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

    /**
     * Pinned so this DTO can gain fields without invalidating entries
     * already sitting in Redis.
     *
     * Without an explicit value the JVM derives one from the class
     * structure, so adding a single field changes it and every cached entry
     * written by the previous build fails to deserialise with
     * InvalidClassException on the next read. Spring's default cache error
     * handler rethrows that, which turns a routine DTO change into a 500 on
     * every browse request until the TTL drains - see CacheConfig, which
     * now also makes that survivable.
     *
     * Java's rules make ADDING a field compatible once this is pinned;
     * removing or retyping one is not, and still needs a cache flush.
     */
    private static final long serialVersionUID = 1L;

    private final String brand;
    private final long productCount;

    public BrandSummary(String brand, long productCount) {
        this.brand = brand;
        this.productCount = productCount;
    }

    public String getBrand() { return brand; }
    public long getProductCount() { return productCount; }
}
