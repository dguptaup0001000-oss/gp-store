package com.gpstore.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonManagedReference;
import java.math.BigDecimal;
import com.gpstore.enums.OrderStatus;
import com.gpstore.enums.PaymentStatus;

import jakarta.persistence.*;

import java.time.LocalDateTime;
import java.util.List;

@Entity
@Table(name = "orders")
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String orderNumber;

    // Hidden from JSON - same reason as Address.customer: the full Customer
    // object (including their cart, etc.) has no business being nested in
    // every order response. Controllers use an explicit DTO instead (see
    // OrderDetailResponse) for whatever customer info an order response
    // actually needs.
    // DTO refactor complete: all service methods touching order.getCustomer()
    // now carry @Transactional / @Transactional(readOnly = true).
    @ManyToOne(fetch = FetchType.LAZY)
    @JsonIgnore
    private Customer customer;

    // DTO refactor complete: getAllOrders/updateOrderStatus/cancelOrder now
    // return DTOs from @Transactional-covered service methods.
    @ManyToOne(fetch = FetchType.LAZY)
    private Address address;

    // Without the ManagedReference/BackReference pair below (see
    // OrderItem.order), serializing this would infinite-loop: Order ->
    // orderItems -> OrderItem.order -> Order -> orderItems -> ... This was a
    // real, previously-uncaught bug - the admin GET /api/orders endpoint
    // already returns raw Order entities and would have crashed the moment
    // it was called with any order that actually had items.
    @OneToMany(mappedBy = "order")
    @JsonManagedReference
    private List<OrderItem> orderItems;

    private BigDecimal totalAmount;

    private String appliedCouponCode;

    private BigDecimal discountAmount;

    private BigDecimal deliveryFee;

    private Boolean freeDeliveryApplied;

@Enumerated(EnumType.STRING)
private OrderStatus orderStatus;

@Enumerated(EnumType.STRING)
private PaymentStatus paymentStatus;

    private LocalDateTime orderDate;

    private Boolean active;

    /**
     * Exactly-once guard for giving this order's reserved stock back.
     *
     * Three independent paths can decide an order's stock should be
     * returned - an explicit cancellation, the stale-UPI expiry scheduler,
     * and a failed/refunded payment - and they don't coordinate through
     * order status alone. cancelOrder() left a PENDING UPI payment
     * untouched, so a cancelled order's payment stayed eligible for the
     * expiry sweep, which then restored the same stock a second time and
     * silently inflated inventory.
     *
     * Statuses can't express this safely on their own: they answer "what
     * happened to the order", not "has the stock already gone back". This
     * flag answers exactly that one question, and every restore path now
     * checks and sets it while holding the order row lock, so whichever
     * path arrives first wins and the rest become no-ops.
     *
     * Mapped without nullable=false on purpose. The NOT NULL and DEFAULT
     * FALSE live in V7's migration, where they belong; declaring them here
     * too would make ddl-auto=update (which CI and local dev still use) try
     * to ADD COLUMN ... NOT NULL against a table that already has rows,
     * which Postgres rejects and Hibernate then swallows - leaving the
     * column absent and every insert failing. Java-side the field defaults
     * to false and every read goes through Boolean.TRUE.equals(...), so a
     * null from a pre-migration row is treated as "not yet restored", which
     * is the safe interpretation.
     */
    @Column(name = "inventory_restored")
    private Boolean inventoryRestored = false;

    public Order() {
    }

    public Long getId() {
        return id;
    }

    public String getOrderNumber() {
        return orderNumber;
    }

    public void setOrderNumber(String orderNumber) {
        this.orderNumber = orderNumber;
    }

    public Customer getCustomer() {
        return customer;
    }

    public void setCustomer(Customer customer) {
        this.customer = customer;
    }

    public Address getAddress() {
        return address;
    }

    public void setAddress(Address address) {
        this.address = address;
    }

    public List<OrderItem> getOrderItems() {
        return orderItems;
    }

    public void setOrderItems(List<OrderItem> orderItems) {
        this.orderItems = orderItems;
    }

    public BigDecimal getTotalAmount() {
    return totalAmount;
}

public void setTotalAmount(BigDecimal totalAmount) {
    this.totalAmount = totalAmount;
}

    public String getAppliedCouponCode() {
        return appliedCouponCode;
    }

    public void setAppliedCouponCode(String appliedCouponCode) {
        this.appliedCouponCode = appliedCouponCode;
    }

    public BigDecimal getDiscountAmount() {
        return discountAmount;
    }

    public void setDiscountAmount(BigDecimal discountAmount) {
        this.discountAmount = discountAmount;
    }

    public BigDecimal getDeliveryFee() {
        return deliveryFee;
    }

    public void setDeliveryFee(BigDecimal deliveryFee) {
        this.deliveryFee = deliveryFee;
    }

    public Boolean getFreeDeliveryApplied() {
        return freeDeliveryApplied;
    }

    public void setFreeDeliveryApplied(Boolean freeDeliveryApplied) {
        this.freeDeliveryApplied = freeDeliveryApplied;
    }

    public OrderStatus getOrderStatus() {
        return orderStatus;
    }

  public void setOrderStatus(OrderStatus orderStatus) {
        this.orderStatus = orderStatus;
    }

    public PaymentStatus getPaymentStatus() {
        return paymentStatus;
    }

    public void setPaymentStatus(PaymentStatus paymentStatus) {
        this.paymentStatus = paymentStatus;
    }

    public LocalDateTime getOrderDate() {
        return orderDate;
    }

    public void setOrderDate(LocalDateTime orderDate) {
        this.orderDate = orderDate;
    }

    public Boolean getActive() {
        return active;
    }

    public void setActive(Boolean active) {
        this.active = active;
    }

    public Boolean getInventoryRestored() {
        return inventoryRestored;
    }

    public void setInventoryRestored(Boolean inventoryRestored) {
        this.inventoryRestored = inventoryRestored;
    }
}