package com.gpstore.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * One thing that is true about a variant: {@code RAM = 8 GB}.
 *
 * <p>WHY NAME/VALUE AND NOT COLUMNS. {@code product_variants} describes a
 * variant with a number and a unit - 1 kg, 500 g, 1 L - which is a grocer's
 * shelf written into a schema. GP-STORE sells phones, sarees, shoes, medicine,
 * hardware and plates of food, and a real merchant was being asked to type
 * "8" into a box labelled "Pack size" because his phone has 8 GB of RAM.
 *
 * <p>A column per trade needs a migration for the trade after it. A row per
 * fact does not: "RAM"/"Storage"/"Colour" for a phone, "Size"/"Colour" for a
 * shoe, "Strength"/"Pack" for medicine, and whatever the next trade turns out
 * to need. The UI may offer a category-shaped TEMPLATE of which names to ask
 * for - that is a convenience on top, and nothing down here knows about it.
 *
 * <p>ADDITIVE. {@code quantity} and {@code unit} still mean the pack size and
 * are still what weighs a basket. A variant with no attributes at all is an
 * ordinary variant, which is what every row on Shop #1 is.
 *
 * <p>CATALOGUE-LEVEL, NOT SHOP-LEVEL, and deliberately so: how much RAM a
 * phone has is a fact about the phone, not about who sells it. What each shop
 * charges for it is {@code shop_product_variants}. Who may edit these rows is
 * therefore a real question, answered in ShopVariantEditing: a merchant edits
 * them only while their shop is the ONLY shop listing the variant.
 */
@Entity
@Table(name = "product_variant_attributes")
public class ProductVariantAttribute {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_variant_id", nullable = false)
    private Long productVariantId;

    /** "RAM", "Storage", "Colour", "Size", "Material", "Strength". */
    @Column(nullable = false, length = 60)
    private String name;

    /** "8 GB", "128 GB", "Black", "9", "Silk", "500 mg". */
    @Column(nullable = false, length = 160)
    private String value;

    /** The order the merchant chose, which is the order the customer reads. */
    @Column(name = "display_order", nullable = false)
    private Integer displayOrder = 0;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onInsert() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
        if (displayOrder == null) {
            displayOrder = 0;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getProductVariantId() { return productVariantId; }
    public void setProductVariantId(Long productVariantId) { this.productVariantId = productVariantId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getValue() { return value; }
    public void setValue(String value) { this.value = value; }

    public Integer getDisplayOrder() { return displayOrder; }
    public void setDisplayOrder(Integer displayOrder) { this.displayOrder = displayOrder; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
