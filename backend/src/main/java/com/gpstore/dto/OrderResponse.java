package com.gpstore.dto;

import java.time.LocalDateTime;
import java.math.BigDecimal;

public class OrderResponse {

    private Long orderId;
    private String orderNumber;
    private BigDecimal totalAmount;
    private String orderStatus;
    private String paymentStatus;
    private LocalDateTime orderDate;

    /**
     * SAME_DAY or NEXT_MORNING, so the history can say which this was.
     *
     * <p>NULL FOR EVERY ORDER PLACED BEFORE THIS FEATURE, and shown as nothing
     * rather than guessed at. Labelling a March order "Same-day" because it
     * happens to have been placed at 2pm would state a fact about a rule that
     * did not exist when it was delivered.
     */
    private String deliveryType;

    /** The day it was scheduled for. Null for the same reason as above. */
    private java.time.LocalDate scheduledDeliveryDate;

    public String getDeliveryType() {
        return deliveryType;
    }

    public void setDeliveryType(String deliveryType) {
        this.deliveryType = deliveryType;
    }

    public java.time.LocalDate getScheduledDeliveryDate() {
        return scheduledDeliveryDate;
    }

    public void setScheduledDeliveryDate(java.time.LocalDate scheduledDeliveryDate) {
        this.scheduledDeliveryDate = scheduledDeliveryDate;
    }
    // Only populated for the admin "all orders" list - null for a
    // customer's own /my-orders list, where it would be redundant (they
    // already know it's their own order).
    private String customerName;

    public String getCustomerName() {
        return customerName;
    }

    public void setCustomerName(String customerName) {
        this.customerName = customerName;
    }

    public Long getOrderId() {
        return orderId;
    }

    public void setOrderId(Long orderId) {
        this.orderId = orderId;
    }

    public String getOrderNumber() {
        return orderNumber;
    }

    public void setOrderNumber(String orderNumber) {
        this.orderNumber = orderNumber;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public void setTotalAmount(BigDecimal totalAmount) {
        this.totalAmount = totalAmount;
    }

    public String getOrderStatus() {
        return orderStatus;
    }

    public void setOrderStatus(String orderStatus) {
        this.orderStatus = orderStatus;
    }

    public String getPaymentStatus() {
        return paymentStatus;
    }

    public void setPaymentStatus(String paymentStatus) {
        this.paymentStatus = paymentStatus;
    }

    public LocalDateTime getOrderDate() {
        return orderDate;
    }

    public void setOrderDate(LocalDateTime orderDate) {
        this.orderDate = orderDate;
    }
}