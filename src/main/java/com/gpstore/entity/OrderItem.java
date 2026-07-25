package com.gpstore.entity;

import com.fasterxml.jackson.annotation.JsonBackReference;
import java.math.BigDecimal;

import jakarta.persistence.*;

@Entity
@Table(name = "order_items")
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JsonBackReference
    private Order order;

    @ManyToOne
    private ProductVariant productVariant;

    private Integer quantity;

  private BigDecimal price;
private BigDecimal totalPrice;

    // Snapshotted at order time - never recomputed later from current
    // category/variant settings, so a past order's tax figure stays correct
    // even if you change GST rates in the future.
    private BigDecimal gstRate;

    private Boolean active;
    
    public Order getOrder() {
    return order;
}

public void setOrder(Order order) {
    this.order = order;
}

public ProductVariant getProductVariant() {
    return productVariant;
}

public void setProductVariant(ProductVariant productVariant) {
    this.productVariant = productVariant;
}

public Integer getQuantity() {
    return quantity;
}

public void setQuantity(Integer quantity) {
    this.quantity = quantity;
}

public BigDecimal getPrice() {
    return price;
}

public void setPrice(BigDecimal price) {
    this.price = price;
}

public BigDecimal getTotalPrice() {
    return totalPrice;
}

public void setTotalPrice(BigDecimal totalPrice) {
    this.totalPrice = totalPrice;
}

public BigDecimal getGstRate() {
    return gstRate;
}

public void setGstRate(BigDecimal gstRate) {
    this.gstRate = gstRate;
}

public Boolean getActive() {
    return active;
}

public void setActive(Boolean active) {
    this.active = active;
}
}