package com.gpstore.dto.response;

import com.gpstore.entity.Address;
import com.gpstore.entity.Delivery;
import com.gpstore.entity.Order;

import java.time.LocalDateTime;

/**
 * What a delivery partner's own app screen shows for one assigned delivery -
 * real address and customer contact info (they need it to actually deliver
 * and coordinate), but nothing about the order's items, pricing, or payment -
 * a delivery partner has no legitimate need to see what someone bought or
 * how much they paid, only where to take it and who to call.
 */
public class MyDeliveryResponse {

    private final Long deliveryId;
    private final Long orderId;
    private final String orderNumber;
    private final String deliveryStatus;
    private final LocalDateTime estimatedDeliveryTime;
    private final LocalDateTime assignedAt;
    private final String customerName;
    private final String customerPhone;
    private final String deliveryAddress;
    // Precise coordinates for map navigation - a delivery partner needs to
    // actually get there, not just read a formatted address string that
    // would need imprecise re-geocoding by whatever maps app they use.
    private final Double latitude;
    private final Double longitude;

    /**
     * The statuses this delivery may move to next.
     *
     * SENT SO THE APP NEVER HAS TO KNOW THE RULES. The worker screen draws one
     * button per entry here and nothing else, so there is exactly one place
     * the state machine lives (DeliveryStatusTransitions), a phone running an
     * old build cannot offer a move that has since been removed, and no worker
     * is ever shown a button the server is going to refuse.
     *
     * It is a convenience, not a control: the server re-checks the transition
     * on the way in regardless of what it previously said was allowed.
     */
    private final java.util.List<String> allowedNext;

    public MyDeliveryResponse(Long deliveryId, Long orderId, String orderNumber, String deliveryStatus,
                               LocalDateTime estimatedDeliveryTime, LocalDateTime assignedAt,
                               String customerName, String customerPhone, String deliveryAddress,
                               Double latitude, Double longitude, java.util.List<String> allowedNext) {
        this.deliveryId = deliveryId;
        this.orderId = orderId;
        this.orderNumber = orderNumber;
        this.deliveryStatus = deliveryStatus;
        this.estimatedDeliveryTime = estimatedDeliveryTime;
        this.assignedAt = assignedAt;
        this.customerName = customerName;
        this.customerPhone = customerPhone;
        this.deliveryAddress = deliveryAddress;
        this.latitude = latitude;
        this.longitude = longitude;
        this.allowedNext = allowedNext == null ? java.util.List.of() : java.util.List.copyOf(allowedNext);
    }

    public static MyDeliveryResponse from(Delivery delivery) {
        Order order = delivery.getOrder();
        Address address = order != null ? order.getAddress() : null;

        String fullAddress = address == null ? null
                : address.getHouseNo() + ", " + address.getArea()
                        + (address.getLandmark() != null ? ", " + address.getLandmark() : "")
                        + ", " + address.getCity() + " - " + address.getPincode();

        return new MyDeliveryResponse(
                delivery.getId(),
                order != null ? order.getId() : null,
                order != null ? order.getOrderNumber() : null,
                delivery.getDeliveryStatus(),
                delivery.getEstimatedDeliveryTime(),
                delivery.getAssignedAt(),
                address != null ? address.getFullName() : null,
                address != null ? address.getMobileNumber() : null,
                fullAddress,
                address != null ? address.getLatitude() : null,
                address != null ? address.getLongitude() : null,
                com.gpstore.delivery.DeliveryStatusTransitions.nextFrom(
                                com.gpstore.enums.DeliveryStatus.parse(delivery.getDeliveryStatus()).orElse(null))
                        .stream().map(Enum::name).toList()
        );
    }

    public Long getDeliveryId() { return deliveryId; }
    public Long getOrderId() { return orderId; }
    public String getOrderNumber() { return orderNumber; }
    public String getDeliveryStatus() { return deliveryStatus; }
    public LocalDateTime getEstimatedDeliveryTime() { return estimatedDeliveryTime; }
    public LocalDateTime getAssignedAt() { return assignedAt; }
    public String getCustomerName() { return customerName; }
    public String getCustomerPhone() { return customerPhone; }
    public String getDeliveryAddress() { return deliveryAddress; }
    public Double getLatitude() { return latitude; }
    public Double getLongitude() { return longitude; }
    public java.util.List<String> getAllowedNext() { return allowedNext; }
}
