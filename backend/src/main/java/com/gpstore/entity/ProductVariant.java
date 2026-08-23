package com.gpstore.entity;

import com.fasterxml.jackson.annotation.JsonBackReference;
import com.fasterxml.jackson.annotation.JsonProperty;
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

    // @JsonBackReference protects this from JSON serialization. Verified every
        // call site that touches variant.getProduct() (placeOrder, addToCart,
        // previewCheckout, InvoiceService, the UPI-expiry scheduled job via
        // restoreInventoryForOrder) is either @Transactional or does not touch
        // getProduct() at all - safe to convert to LAZY.
    @ManyToOne(fetch = FetchType.LAZY)
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
    // WRITE_ONLY: an admin can still set this via PUT/POST, but it was
    // missing this protection entirely before - meaning every customer
    // viewing any product was seeing your wholesale margin.
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private BigDecimal costPrice;

    private Integer displayOrder;

    private BigDecimal sellingPrice;

    /**
     * What one unit of this variant weighs, in grams. The OVERRIDE, not the
     * source of truth.
     *
     * Most of the shelf already states its weight in the pack size: 5 kg atta,
     * 500 g haldi, 1 l oil. OrderWeightCalculator reads those directly, so
     * this column only needs filling where the pack size cannot say - anything
     * sold by the piece, and anything whose volume is a poor proxy for its
     * mass. Where it is set it wins outright.
     *
     * Null is the normal state and not a defect.
     */
    @Column(name = "weight_grams")
    private BigDecimal weightGrams;

    // Optional exception to the category's GST rate for this specific variant.
    // Null means "use the category's rate" - most products won't need this.
    private BigDecimal gstRateOverride;

    private Boolean active;
}