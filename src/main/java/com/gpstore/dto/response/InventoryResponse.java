package com.gpstore.dto.response;

import com.gpstore.entity.Inventory;

public class InventoryResponse {

    private final Long id;
    private final Long variantId;
    private final String productName;
    private final String productBrand;
    private final Double variantQuantity;
    private final String unit;
    private final Integer stock;
    private final Integer reservedStock;
    private final Integer minimumStock;
    private final Integer maximumStock;

    public InventoryResponse(Long id, Long variantId, String productName, String productBrand,
                              Double variantQuantity, String unit, Integer stock, Integer reservedStock,
                              Integer minimumStock, Integer maximumStock) {
        this.id = id;
        this.variantId = variantId;
        this.productName = productName;
        this.productBrand = productBrand;
        this.variantQuantity = variantQuantity;
        this.unit = unit;
        this.stock = stock;
        this.reservedStock = reservedStock;
        this.minimumStock = minimumStock;
        this.maximumStock = maximumStock;
    }

    public static InventoryResponse from(Inventory inventory) {
        var variant = inventory.getProductVariant();
        var product = variant != null ? variant.getProduct() : null;

        return new InventoryResponse(
                inventory.getId(),
                variant != null ? variant.getId() : null,
                product != null ? product.getName() : null,
                product != null ? product.getBrand() : null,
                variant != null ? variant.getQuantity() : null,
                variant != null ? variant.getUnit() : null,
                inventory.getStock(),
                inventory.getReservedStock(),
                inventory.getMinimumStock(),
                inventory.getMaximumStock()
        );
    }

    public Long getId() { return id; }
    public Long getVariantId() { return variantId; }
    public String getProductName() { return productName; }
    public String getProductBrand() { return productBrand; }
    public Double getVariantQuantity() { return variantQuantity; }
    public String getUnit() { return unit; }
    public Integer getStock() { return stock; }
    public Integer getReservedStock() { return reservedStock; }
    public Integer getMinimumStock() { return minimumStock; }
    public Integer getMaximumStock() { return maximumStock; }
}
