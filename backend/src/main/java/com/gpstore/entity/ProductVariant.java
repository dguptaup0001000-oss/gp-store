package com.gpstore.entity;

import com.fasterxml.jackson.annotation.JsonBackReference;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import jakarta.persistence.*;

@Entity
@Table(name = "product_variants")

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ProductVariant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
@JsonBackReference
private Product product;

    private Double quantity;

    private String unit;

    private String barcode;
    
    private String sku;
    
    private String imageUrl;
    
    private Boolean available;

    private BigDecimal mrp;

    // What YOU pay to stock this item - never shown to customers. This is what
    // makes the profit-based free-delivery rule possible: profit = sellingPrice
    // - costPrice, not just sellingPrice on its own.
    private BigDecimal costPrice;

    private Integer displayOrder;

    private BigDecimal sellingPrice;

    // Optional exception to the category's GST rate for this specific variant.
    // Null means "use the category's rate" - most products won't need this.
    private BigDecimal gstRateOverride;

    private Boolean active;
}