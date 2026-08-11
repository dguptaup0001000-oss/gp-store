package com.gpstore.dto.response;

import com.gpstore.entity.Delivery;
import com.gpstore.entity.DeliveryBatch;
import com.gpstore.entity.Order;

import java.time.LocalDateTime;

public class DeliveryResponse {

    private final Long deliveryId;
    private final Long orderId;
    private final String orderNumber;
    private final Long batchId;
    private final String deliveryStatus;
    private final Double distanceKm;
    private final LocalDateTime estimatedDeliveryTime;
    private final LocalDateTime deliveredAt;
    private final String deliveryPersonName;
    private final String deliveryPersonPhone;
    private final LocalDateTime assignedAt;
    private final Boolean guaranteeBreached;
    private final Boolean active;

    public DeliveryResponse(Long deliveryId, Long orderId, String orderNumber, Long batchId,
                             String deliveryStatus, Double distanceKm, LocalDateTime estimatedDeliveryTime,
                             LocalDateTime deliveredAt, String deliveryPersonName, String deliveryPersonPhone,
                             LocalDateTime assignedAt, Boolean guaranteeBreached, Boolean active) {
        this.deliveryId = deliveryId;
        this.orderId = orderId;
        this.orderNumber = orderNumber;
        this.batchId = batchId;
        this.deliveryStatus = deliveryStatus;
        this.distanceKm = distanceKm;
        this.estimatedDeliveryTime = estimatedDeliveryTime;
        this.deliveredAt = deliveredAt;
        this.deliveryPersonName = deliveryPersonName;
        this.deliveryPersonPhone = deliveryPersonPhone;
        this.assignedAt = assignedAt;
        this.guaranteeBreached = guaranteeBreached;
        this.active = active;
    }

    public static DeliveryResponse from(Delivery delivery) {
        Order order = delivery.getOrder();
        DeliveryBatch batch = delivery.getBatch();

        return new DeliveryResponse(
                delivery.getId(),
                order != null ? order.getId() : null,
                order != null ? order.getOrderNumber() : null,
                batch != null ? batch.getId() : null,
                delivery.getDeliveryStatus(),
                delivery.getDistanceKm(),
                delivery.getEstimatedDeliveryTime(),
                delivery.getDeliveredAt(),
                delivery.getDeliveryPersonName(),
                delivery.getDeliveryPersonPhone(),
                delivery.getAssignedAt(),
                delivery.getGuaranteeBreached(),
                delivery.getActive()
        );
    }

    public Long getDeliveryId() { return deliveryId; }
    public Long getOrderId() { return orderId; }
    public String getOrderNumber() { return orderNumber; }
    public Long getBatchId() { return batchId; }
    public String getDeliveryStatus() { return deliveryStatus; }
    public Double getDistanceKm() { return distanceKm; }
    public LocalDateTime getEstimatedDeliveryTime() { return estimatedDeliveryTime; }
    public LocalDateTime getDeliveredAt() { return deliveredAt; }
    public String getDeliveryPersonName() { return deliveryPersonName; }
    public String getDeliveryPersonPhone() { return deliveryPersonPhone; }
    public LocalDateTime getAssignedAt() { return assignedAt; }
    public Boolean getGuaranteeBreached() { return guaranteeBreached; }
    public Boolean getActive() { return active; }
}
