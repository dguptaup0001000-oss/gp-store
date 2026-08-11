package com.gpstore.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "deliveries")
public class Delivery {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Deliberately left EAGER: DeliveryController exposes raw Delivery/List<Delivery>
    // entities with no @Transactional on most endpoints (assignDelivery, getAllDeliveries,
    // getDeliveryById, updateDeliveryStatus, getBreachedDeliveries, etc.). LAZY here would
    // throw LazyInitializationException on those. Revisit alongside a DTO refactor of
    // DeliveryController, not in isolation.
    @OneToOne
    private Order order;

    @ManyToOne
    private DeliveryBatch batch;

    private Double distanceKm;

    private LocalDateTime estimatedDeliveryTime;

    private String deliveryStatus;

    private String deliveryPersonName;

    private String deliveryPersonPhone;

    private LocalDateTime assignedAt;

    private LocalDateTime deliveredAt;

    private Boolean active;

    // True once this delivery has missed its own estimatedDeliveryTime -
    // either detected in real time when marked DELIVERED late, or by the
    // periodic check for deliveries still in transit past their ETA.
    // No auto-refund/compensation is attached to this - it's a review flag
    // for you, by design (see DeliveryService).
    private Boolean guaranteeBreached = false;

    public Boolean getGuaranteeBreached() {
        return guaranteeBreached;
    }

    public void setGuaranteeBreached(Boolean guaranteeBreached) {
        this.guaranteeBreached = guaranteeBreached;
    }

    public Delivery() {
    }

    public Long getId() {
        return id;
    }

    public Order getOrder() {
        return order;
    }

    public void setOrder(Order order) {
        this.order = order;
    }

    public DeliveryBatch getBatch() {
        return batch;
    }

    public void setBatch(DeliveryBatch batch) {
        this.batch = batch;
    }

    public Double getDistanceKm() {
        return distanceKm;
    }

    public void setDistanceKm(Double distanceKm) {
        this.distanceKm = distanceKm;
    }

    public LocalDateTime getEstimatedDeliveryTime() {
        return estimatedDeliveryTime;
    }

    public void setEstimatedDeliveryTime(LocalDateTime estimatedDeliveryTime) {
        this.estimatedDeliveryTime = estimatedDeliveryTime;
    }

    public String getDeliveryStatus() {
        return deliveryStatus;
    }

    public void setDeliveryStatus(String deliveryStatus) {
        this.deliveryStatus = deliveryStatus;
    }

    public String getDeliveryPersonName() {
        return deliveryPersonName;
    }

    public void setDeliveryPersonName(String deliveryPersonName) {
        this.deliveryPersonName = deliveryPersonName;
    }

    public String getDeliveryPersonPhone() {
        return deliveryPersonPhone;
    }

    public void setDeliveryPersonPhone(String deliveryPersonPhone) {
        this.deliveryPersonPhone = deliveryPersonPhone;
    }

    public LocalDateTime getAssignedAt() {
        return assignedAt;
    }

    public void setAssignedAt(LocalDateTime assignedAt) {
        this.assignedAt = assignedAt;
    }

    public LocalDateTime getDeliveredAt() {
        return deliveredAt;
    }

    public void setDeliveredAt(LocalDateTime deliveredAt) {
        this.deliveredAt = deliveredAt;
    }

    public Boolean getActive() {
        return active;
    }

    public void setActive(Boolean active) {
        this.active = active;
    }
}