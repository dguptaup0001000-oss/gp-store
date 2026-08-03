package com.gpstore.dto.response;

import com.gpstore.entity.Delivery;
import com.gpstore.entity.DeliveryPartner;

import java.time.LocalDateTime;

/**
 * What a customer's own app shows on the live tracking screen for their
 * order - the assigned partner's current GPS position plus status/ETA.
 * Deliberately excludes the partner's name/phone (already exposed via the
 * existing delivery lookup) and anything about other orders in their batch.
 */
public class DeliveryTrackingResponse {

    private final Long deliveryId;
    private final String deliveryStatus;
    private final LocalDateTime estimatedDeliveryTime;
    private final Double partnerLatitude;
    private final Double partnerLongitude;
    private final LocalDateTime locationUpdatedAt;

    public DeliveryTrackingResponse(Long deliveryId, String deliveryStatus, LocalDateTime estimatedDeliveryTime,
                                     Double partnerLatitude, Double partnerLongitude, LocalDateTime locationUpdatedAt) {
        this.deliveryId = deliveryId;
        this.deliveryStatus = deliveryStatus;
        this.estimatedDeliveryTime = estimatedDeliveryTime;
        this.partnerLatitude = partnerLatitude;
        this.partnerLongitude = partnerLongitude;
        this.locationUpdatedAt = locationUpdatedAt;
    }

    public static DeliveryTrackingResponse from(Delivery delivery) {
        DeliveryPartner partner = delivery.getBatch() != null ? delivery.getBatch().getDeliveryPartner() : null;

        return new DeliveryTrackingResponse(
                delivery.getId(),
                delivery.getDeliveryStatus(),
                delivery.getEstimatedDeliveryTime(),
                partner != null ? partner.getCurrentLatitude() : null,
                partner != null ? partner.getCurrentLongitude() : null,
                partner != null ? partner.getLocationUpdatedAt() : null
        );
    }

    public Long getDeliveryId() { return deliveryId; }
    public String getDeliveryStatus() { return deliveryStatus; }
    public LocalDateTime getEstimatedDeliveryTime() { return estimatedDeliveryTime; }
    public Double getPartnerLatitude() { return partnerLatitude; }
    public Double getPartnerLongitude() { return partnerLongitude; }
    public LocalDateTime getLocationUpdatedAt() { return locationUpdatedAt; }
}
