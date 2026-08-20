package com.gpstore.dto.response;

import com.gpstore.entity.ProductVariant;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * Explicit response shape instead of returning the raw ProductVariant entity.
 * Deliberately excludes barcode, sku, gstRateOverride, and costPrice - none of
 * these are read by the Flutter app, and costPrice is wholesale-margin data
 * that should never reach a customer-facing response regardless of the
 * entity's own @JsonProperty(WRITE_ONLY) protection.
 *
 * Serializable - see ProductResponse's doc comment (this is nested inside it).
 */
public class VariantResponse implements Serializable {

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

    private final Long id;
    private final Double quantity;
    private final String unit;
    private final String imageUrl;
    private final Boolean available;
    private final BigDecimal mrp;
    private final BigDecimal sellingPrice;
    private final Integer displayOrder;

    public VariantResponse(Long id, Double quantity, String unit, String imageUrl,
                            Boolean available, BigDecimal mrp, BigDecimal sellingPrice,
                            Integer displayOrder) {
        this.id = id;
        this.quantity = quantity;
        this.unit = unit;
        this.imageUrl = imageUrl;
        this.available = available;
        this.mrp = mrp;
        this.sellingPrice = sellingPrice;
        this.displayOrder = displayOrder;
    }

    public static VariantResponse from(ProductVariant variant) {
        if (variant == null) {
            return null;
        }
        return new VariantResponse(
                variant.getId(),
                variant.getQuantity(),
                variant.getUnit(),
                variant.getImageUrl(),
                variant.getAvailable(),
                variant.getMrp(),
                variant.getSellingPrice(),
                variant.getDisplayOrder()
        );
    }

    public Long getId() { return id; }
    public Double getQuantity() { return quantity; }
    public String getUnit() { return unit; }
    public String getImageUrl() { return imageUrl; }
    public Boolean getAvailable() { return available; }
    public BigDecimal getMrp() { return mrp; }
    public BigDecimal getSellingPrice() { return sellingPrice; }
    public Integer getDisplayOrder() { return displayOrder; }
}
