package com.gpstore.entity;

import com.fasterxml.jackson.annotation.JsonBackReference;
import java.math.BigDecimal;

import jakarta.persistence.*;

@Entity
@Table(name = "order_items")
public class OrderItem {

    /**
     * SEQUENCE, not IDENTITY, and that difference is the entire point.
     *
     * Hibernate cannot batch INSERTs for an IDENTITY-generated entity: it
     * must execute each statement immediately to read the generated key
     * back, so the JDBC batch is flushed one row at a time. Measured on a
     * 10-item checkout, that was ten separate INSERT round trips into
     * order_items despite hibernate.jdbc.batch_size=50 and
     * order_inserts=true both being configured - those settings simply never
     * applied to this entity.
     *
     * It matters because those round trips happen inside the checkout
     * transaction while the per-variant inventory locks are held, so each one
     * extends the window another customer buying the same product is blocked
     * for. Invisible on a local socket; ten times the per-query latency
     * against a managed database over a network.
     *
     * allocationSize MUST equal the sequence's INCREMENT BY in V10 (50).
     * They are a matched pair: if Hibernate believes it owns a wider block
     * than the database actually reserved, it hands out ids that later
     * collide. Change both together or neither.
     *
     * Only OrderItem is changed. Order and the rest keep IDENTITY - they are
     * inserted one row at a time, so batching has nothing to offer them and
     * the change would be risk without benefit.
     */
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "order_items_seq")
    @SequenceGenerator(name = "order_items_seq", sequenceName = "order_items_seq", allocationSize = 50)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JsonBackReference
    private Order order;

    @ManyToOne(fetch = FetchType.LAZY)
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