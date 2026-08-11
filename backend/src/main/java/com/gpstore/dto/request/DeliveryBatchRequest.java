package com.gpstore.dto.request;

/**
 * Used for both creating a batch (deliveryPartnerId + area required, status ignored)
 * and updating one (status required, deliveryPartnerId/area ignored). The service
 * methods only read the fields relevant to their operation - this keeps one shape
 * instead of two near-identical DTOs for a low-traffic admin surface.
 */
public class DeliveryBatchRequest {

    private Long deliveryPartnerId;
    private String area;
    private String status;

    public Long getDeliveryPartnerId() { return deliveryPartnerId; }
    public void setDeliveryPartnerId(Long deliveryPartnerId) { this.deliveryPartnerId = deliveryPartnerId; }

    public String getArea() { return area; }
    public void setArea(String area) { this.area = area; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
