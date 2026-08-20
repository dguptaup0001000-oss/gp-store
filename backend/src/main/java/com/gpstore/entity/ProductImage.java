package com.gpstore.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * One image in a product's gallery. See V11__add_product_images.sql for why
 * this is a table rather than image1..image5 columns on products.
 *
 * Owned by the PRODUCT, not the variant: ProductVariant.imageUrl stays the
 * single small listing thumbnail, and this is the detail-page gallery. That
 * split is what keeps a product grid loading one image per card instead of
 * five.
 */
@Entity
@Table(name = "product_images")
public class ProductImage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * LAZY, like every other association in this codebase: a gallery is only
     * ever loaded deliberately, and an EAGER product here would drag the
     * product graph into every image read.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(name = "image_url", nullable = false, length = 500)
    private String imageUrl;

    /** Explicit display order - see the migration for why this is not implicit. */
    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Product getProduct() { return product; }
    public void setProduct(Product product) { this.product = product; }

    public String getImageUrl() { return imageUrl; }
    public void setImageUrl(String imageUrl) { this.imageUrl = imageUrl; }

    public Integer getSortOrder() { return sortOrder; }
    public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
