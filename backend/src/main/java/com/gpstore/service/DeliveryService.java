package com.gpstore.service;

import com.gpstore.entity.Delivery;
import com.gpstore.entity.DeliveryBatch;
import com.gpstore.entity.DeliveryPartner;
import com.gpstore.entity.Order;
import com.gpstore.enums.OrderStatus;
import com.gpstore.exception.BadRequestException;
import com.gpstore.exception.ResourceNotFoundException;
import com.gpstore.repository.DeliveryPartnerRepository;
import com.gpstore.repository.DeliveryRepository;
import com.gpstore.repository.OrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class DeliveryService {

    private final DeliveryRepository deliveryRepository;
    private final OrderRepository orderRepository;
    private final DeliveryPartnerRepository deliveryPartnerRepository;
    private final DeliveryPartnerService deliveryPartnerService;
    private final DeliveryBatchService deliveryBatchService;
    private final DeliveryEstimateService deliveryEstimateService;
    private final NotificationService notificationService;
    private final AuditLogService auditLogService;
    private final int bulkOrderItemThreshold;

    public DeliveryService(
            DeliveryRepository deliveryRepository,
            OrderRepository orderRepository,
            DeliveryPartnerRepository deliveryPartnerRepository,
            DeliveryPartnerService deliveryPartnerService,
            DeliveryBatchService deliveryBatchService,
            DeliveryEstimateService deliveryEstimateService,
            NotificationService notificationService,
            AuditLogService auditLogService,
            @org.springframework.beans.factory.annotation.Value("${delivery.bulk-order-item-threshold}") int bulkOrderItemThreshold) {

        this.deliveryRepository = deliveryRepository;
        this.orderRepository = orderRepository;
        this.deliveryPartnerRepository = deliveryPartnerRepository;
        this.deliveryPartnerService = deliveryPartnerService;
        this.deliveryBatchService = deliveryBatchService;
        this.deliveryEstimateService = deliveryEstimateService;
        this.notificationService = notificationService;
        this.auditLogService = auditLogService;
        this.bulkOrderItemThreshold = bulkOrderItemThreshold;
    }

    /**
     * Lets an admin manually decide the vehicle type for THIS order (e.g. "I
     * know this is a big order, use pickup") instead of relying on the
     * automatic item-count threshold - the system still auto-picks whichever
     * available partner of that type has the lightest load, so you're only
     * overriding the vehicle decision, not doing partner assignment by hand.
     */
    @Transactional
    public Delivery assignWithVehicleType(Long orderId, String vehicleType) {
        DeliveryPartner partner = deliveryPartnerService.getLeastLoadedAvailablePartner(vehicleType);
        return assignDelivery(orderId, partner.getId());
    }

    /**
     * Auto-assigns to whichever available partner currently has the lightest
     * load - the real operational path for 10 partners. Routes bulk orders
     * (total item count >= bulkOrderItemThreshold) to a PICKUP-type partner
     * when one is free, since that's more than a bike can reasonably carry;
     * falls back to any available partner otherwise so a bulk order never
     * gets stuck waiting for a specific vehicle. assignDelivery() with an
     * explicit partner ID, and assignWithVehicleType() with an explicit
     * vehicle type, both stay available for manual overrides.
     */
    @Transactional
    public Delivery autoAssignDelivery(Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));

        int totalItemCount = order.getOrderItems() == null ? 0 :
                order.getOrderItems().stream().mapToInt(item -> item.getQuantity() == null ? 0 : item.getQuantity()).sum();

        String preferredVehicleType = totalItemCount >= bulkOrderItemThreshold ? "PICKUP" : "BIKE";

        DeliveryPartner partner = deliveryPartnerService.getLeastLoadedAvailablePartner(preferredVehicleType);
        return assignDelivery(orderId, partner.getId());
    }

    /**
     * Assigns an order to a delivery partner's current batch (auto-opening a new
     * batch if the current one already has 20 orders), and computes a
     * distance-based ETA - floor 10 minutes, cap 2 hours. This is the real
     * "10 min to 2 hour delivery" rule, not a flat promise.
     */
    @Transactional
    public Delivery assignDelivery(Long orderId, Long deliveryPartnerId) {

        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));

        if (deliveryRepository.findByOrderId(orderId).isPresent()) {
            throw new BadRequestException("This order already has a delivery assigned");
        }

        DeliveryPartner partner = deliveryPartnerRepository.findById(deliveryPartnerId)
                .orElseThrow(() -> new ResourceNotFoundException("Delivery partner not found"));

        String area = order.getAddress() != null && order.getAddress().getArea() != null
                ? order.getAddress().getArea()
                : "UNASSIGNED";

        DeliveryBatch batch = deliveryBatchService.getOrCreateOpenBatch(deliveryPartnerId, area);

        Double lat = order.getAddress() != null ? order.getAddress().getLatitude() : null;
        Double lng = order.getAddress() != null ? order.getAddress().getLongitude() : null;

        double distanceKm = deliveryEstimateService.distanceFromStoreKm(lat, lng);
        int estimateMinutes = deliveryEstimateService.estimateMinutes(lat, lng);

        Delivery delivery = new Delivery();
        delivery.setOrder(order);
        delivery.setBatch(batch);
        delivery.setDistanceKm(Double.isNaN(distanceKm) ? null : distanceKm);
        delivery.setDeliveryStatus("ASSIGNED");
        delivery.setDeliveryPersonName(partner.getName());
        delivery.setDeliveryPersonPhone(partner.getMobile());
        delivery.setAssignedAt(LocalDateTime.now());
        delivery.setEstimatedDeliveryTime(LocalDateTime.now().plusMinutes(estimateMinutes));
        delivery.setActive(true);

        return deliveryRepository.save(delivery);
    }

    public List<Delivery> getAllDeliveries() {
        return deliveryRepository.findAll();
    }

    public Optional<Delivery> getDeliveryById(Long id) {
        return deliveryRepository.findById(id);
    }

    public Optional<Delivery> getDeliveryByOrderId(Long orderId) {
        return deliveryRepository.findByOrderId(orderId);
    }

    /** Returns the delivery only if the order belongs to this customer - closes the IDOR. */
    public Optional<Delivery> getOwnedDeliveryByOrderId(Long orderId, Long callerCustomerId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));

        if (order.getCustomer() == null || !order.getCustomer().getId().equals(callerCustomerId)) {
            throw new ResourceNotFoundException("Order not found");
        }

        return deliveryRepository.findByOrderId(orderId);
    }

    @Transactional
    public Delivery updateDeliveryStatus(Long deliveryId, String status) {

        Delivery delivery = deliveryRepository.findById(deliveryId)
                .orElseThrow(() -> new ResourceNotFoundException("Delivery not found"));

        delivery.setDeliveryStatus(status);

        Order order = delivery.getOrder();

        if ("OUT_FOR_DELIVERY".equalsIgnoreCase(status) && order != null) {
            order.setOrderStatus(OrderStatus.OUT_FOR_DELIVERY);
            orderRepository.save(order);
            notificationService.notifyOrderStatusChange(order, OrderStatus.OUT_FOR_DELIVERY);
        }

        if ("DELIVERED".equalsIgnoreCase(status)) {

            LocalDateTime deliveredAt = LocalDateTime.now();
            delivery.setDeliveredAt(deliveredAt);

            // Real-time half of the guarantee check: was it delivered after
            // the ETA we promised at assignment time? No auto-refund/action -
            // just flags it for manual review, by design.
            if (delivery.getEstimatedDeliveryTime() != null
                    && deliveredAt.isAfter(delivery.getEstimatedDeliveryTime())
                    && !Boolean.TRUE.equals(delivery.getGuaranteeBreached())) {
                delivery.setGuaranteeBreached(true);
                auditLogService.log("DELIVERY_GUARANTEE_BREACHED", "Delivery", delivery.getId(),
                        "delivered late: promised " + delivery.getEstimatedDeliveryTime() + ", actual " + deliveredAt);
            }

            if (order != null) {
                order.setOrderStatus(OrderStatus.DELIVERED);
                orderRepository.save(order);
                notificationService.notifyOrderStatusChange(order, OrderStatus.DELIVERED);
            }
        }

        return deliveryRepository.save(delivery);
    }

    /**
     * Proactive half of the guarantee check: runs every 15 minutes, finds
     * deliveries still in transit that have already passed their promised ETA,
     * and flags them - so you find out a delivery is running late WHILE it's
     * happening, not only after a customer complains. No auto-refund/action;
     * this is deliberately just a review signal (see AuditLog + the
     * /api/deliveries/breached endpoint).
     */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 15 * 60 * 1000)
    @Transactional
    public void flagLateDeliveries() {
        List<Delivery> lateDeliveries = deliveryRepository.findLateNotYetFlagged(LocalDateTime.now());

        for (Delivery delivery : lateDeliveries) {
            delivery.setGuaranteeBreached(true);
            deliveryRepository.save(delivery);
            auditLogService.log("DELIVERY_GUARANTEE_BREACHED", "Delivery", delivery.getId(),
                    "still in transit past promised ETA of " + delivery.getEstimatedDeliveryTime());
        }
    }

    /** The manual-review list - every delivery that has ever missed its promised ETA. */
    public List<Delivery> getBreachedDeliveries() {
        return deliveryRepository.findByGuaranteeBreachedTrueOrderByEstimatedDeliveryTimeDesc();
    }
}
