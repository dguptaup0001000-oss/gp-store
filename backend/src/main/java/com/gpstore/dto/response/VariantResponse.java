package com.gpstore.dto.response;

import com.gpstore.entity.ProductVariant;

import java.math.BigDecimal;

/**
 * Explicit response shape instead of returning the raw ProductVariant entity.
 * Deliberately excludes barcode, sku, gstRateOverride, and costPrice - none of
 * these are read by the Flutter app, and costPrice is wholesale-margin data
 * that should never reach a customer-facing response regardless of the
 * entity's own @JsonProperty(WRITE_ONLY) protection.
 */
public class VariantResponse {

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
