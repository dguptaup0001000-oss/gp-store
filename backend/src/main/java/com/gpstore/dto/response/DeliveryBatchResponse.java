package com.gpstore.dto.response;

import com.gpstore.entity.DeliveryBatch;

import java.time.LocalDateTime;

public class DeliveryBatchResponse {

    private final Long id;
    private final String batchNumber;
    private final String area;
    private final String status;
    private final LocalDateTime createdAt;
    private final Boolean active;
    private final Long deliveryPartnerId;
    private final String deliveryPartnerName;
    private final String deliveryPartnerMobile;

    public DeliveryBatchResponse(Long id, String batchNumber, String area, String status,
                                  LocalDateTime createdAt, Boolean active,
                                  Long deliveryPartnerId, String deliveryPartnerName, String deliveryPartnerMobile) {
        this.id = id;
        this.batchNumber = batchNumber;
        this.area = area;
        this.status = status;
        this.createdAt = createdAt;
        this.active = active;
        this.deliveryPartnerId = deliveryPartnerId;
        this.deliveryPartnerName = deliveryPartnerName;
        this.deliveryPartnerMobile = deliveryPartnerMobile;
    }

    public static DeliveryBatchResponse from(DeliveryBatch batch) {
        return new DeliveryBatchResponse(
                batch.getId(),
                batch.getBatchNumber(),
                batch.getArea(),
                batch.getStatus(),
                batch.getCreatedAt(),
                batch.getActive(),
                batch.getDeliveryPartner() != null ? batch.getDeliveryPartner().getId() : null,
                batch.getDeliveryPartner() != null ? batch.getDeliveryPartner().getName() : null,
                batch.getDeliveryPartner() != null ? batch.getDeliveryPartner().getMobile() : null
        );
    }

    public Long getId() { return id; }
    public String getBatchNumber() { return batchNumber; }
    public String getArea() { return area; }
    public String getStatus() { return status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public Boolean getActive() { return active; }
    public Long getDeliveryPartnerId() { return deliveryPartnerId; }
    public String getDeliveryPartnerName() { return deliveryPartnerName; }
    public String getDeliveryPartnerMobile() { return deliveryPartnerMobile; }
}
