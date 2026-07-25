package com.gpstore.entity;

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

    @ManyToOne
    private Customer customer;

    @ManyToOne
    private Address address;

    @OneToMany(mappedBy = "order")
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
}