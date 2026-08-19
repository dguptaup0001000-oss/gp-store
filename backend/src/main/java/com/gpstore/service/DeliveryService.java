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

    /** Max deliveries flagged as late in one scheduled sweep - see flagLateDeliveries. */
    private static final int LATE_FLAG_BATCH_SIZE = 500;

    private final DeliveryRepository deliveryRepository;
    private final OrderRepository orderRepository;
    private final DeliveryPartnerRepository deliveryPartnerRepository;
    private final DeliveryPartnerService deliveryPartnerService;
    private final DeliveryBatchService deliveryBatchService;
    private final DeliveryEstimateService deliveryEstimateService;
    private final NotificationService notificationService;
    private final AuditLogService auditLogService;
    private final PaymentService paymentService;
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
            PaymentService paymentService,
            @org.springframework.beans.factory.annotation.Value("${delivery.bulk-order-item-threshold}") int bulkOrderItemThreshold) {

        this.deliveryRepository = deliveryRepository;
        this.orderRepository = orderRepository;
        this.deliveryPartnerRepository = deliveryPartnerRepository;
        this.deliveryPartnerService = deliveryPartnerService;
        this.deliveryBatchService = deliveryBatchService;
        this.deliveryEstimateService = deliveryEstimateService;
        this.notificationService = notificationService;
        this.auditLogService = auditLogService;
        this.paymentService = paymentService;
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
    public com.gpstore.dto.response.DeliveryResponse assignWithVehicleType(Long orderId, String vehicleType) {
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
    public com.gpstore.dto.response.DeliveryResponse autoAssignDelivery(Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));

        int totalItemCount = order.getOrderItems() == null ? 0 :
                order.getOrderItems().stream().mapToInt(item -> item.getQuantity() == null ? 0 : item.getQuantity()).sum();

        String preferredVehicleType = totalItemCount >= bulkOrderItemThreshold ? "PICKUP" : "BIKE";

        DeliveryPartner partner = deliveryPartnerService.getLeastLoadedAvailablePartner(preferredVehicleType);
        return assignDelivery(orderId, partner.getId());
    }

    /**
     * Best-effort wrapper for OrderService.placeOrder to call right after an
     * order is placed - this used to not exist anywhere at all, meaning NO
     * order in this entire app ever got assigned to a delivery partner
     * unless someone manually called the assign endpoint directly. This is
     * the fix, designed specifically so a failure here (e.g. no delivery
     * partners currently available, which is entirely plausible for a small
     * roster) can NEVER cause the customer's already-successful, already-paid
     * order to fail or roll back:
     * - REQUIRES_NEW propagation means this runs in its own transaction,
     *   completely separate from placeOrder's. Spring marks a transaction
     *   rollback-only the instant an exception exits ANY @Transactional
     *   method within it, even if the caller catches that exception
     *   afterward - so simply catching the exception in placeOrder would
     *   NOT have been enough to protect it. A separate transaction is what
     *   actually prevents that.
     * - Every exception is caught HERE, never rethrown, so placeOrder never
     *   needs to know this failed at all - it just logs it for admin
     *   visibility (the order remains real and valid, just unassigned,
     *   needing manual assignment via the existing /assign endpoint).
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void autoAssignBestEffort(Long orderId) {
        try {
            autoAssignDelivery(orderId);
        } catch (Exception ex) {
            auditLogService.log("AUTO_ASSIGN_FAILED", "Order", orderId,
                    "Order placed but could not be auto-assigned a delivery partner: " + ex.getMessage()
                            + " - needs manual assignment.");
        }
    }

    /**
     * Assigns an order to a delivery partner's current batch (auto-opening a new
     * batch if the current one already has 20 orders), and computes a
     * distance-based ETA - floor 10 minutes, cap 2 hours. This is the real
     * "10 min to 2 hour delivery" rule, not a flat promise.
     */
    @Transactional
    public com.gpstore.dto.response.DeliveryResponse assignDelivery(Long orderId, Long deliveryPartnerId) {

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

        Delivery saved = deliveryRepository.save(delivery);

        // Best-effort, same reasoning as autoAssignBestEffort() above - a
        // notification hiccup must never fail an assignment that already
        // succeeded. notifyPartnerNewAssignment() catches its own
        // exceptions internally, so this call can't throw, but the intent
        // is the same either way: assignment succeeds regardless.
        notificationService.notifyPartnerNewAssignment(partner, saved);

        return com.gpstore.dto.response.DeliveryResponse.from(saved);
    }

    @Transactional(readOnly = true)


    public List<com.gpstore.dto.response.DeliveryResponse> getAllDeliveries() {
        // Delivery.order is EAGER with no @JsonIgnore (see Delivery.java) - if
        // this returned raw entities, every admin list load would nest the
        // full Order graph (address, order items, product variants) for
        // every delivery in the system. Same reason getDeliveryById/
        // getDeliveryByOrderId/getMyOrderDelivery below all map to the DTO too.
        // Unused by the current frontend (confirmed) and admin-only, but was
        // an unbounded findAll() - every delivery ever created, growing
        // forever with order volume. Capped defensively, same as
        // DeliveryBatchService.getAll().
        return deliveryRepository.findAll(org.springframework.data.domain.PageRequest.of(0, 500)).stream()
                .map(com.gpstore.dto.response.DeliveryResponse::from)
                .collect(java.util.stream.Collectors.toList());
    }

    @Transactional(readOnly = true)


    public Optional<com.gpstore.dto.response.DeliveryResponse> getDeliveryById(Long id) {
        return deliveryRepository.findById(id).map(com.gpstore.dto.response.DeliveryResponse::from);
    }

    @Transactional(readOnly = true)


    public Optional<com.gpstore.dto.response.DeliveryResponse> getDeliveryByOrderId(Long orderId) {
        return deliveryRepository.findByOrderId(orderId).map(com.gpstore.dto.response.DeliveryResponse::from);
    }

    /** Returns the delivery only if the order belongs to this customer - closes the IDOR. */
    @Transactional(readOnly = true)

    public Optional<Delivery> getOwnedDeliveryByOrderId(Long orderId, Long callerCustomerId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));

        if (order.getCustomer() == null || !order.getCustomer().getId().equals(callerCustomerId)) {
            throw new ResourceNotFoundException("Order not found");
        }

        return deliveryRepository.findByOrderId(orderId);
    }

    /**
     * Live tracking view for a customer's own order - same ownership check
     * as getOwnedDeliveryByOrderId() above, shaped down to just the
     * assigned partner's current GPS position (see DeliveryTrackingResponse).
     */
    @Transactional(readOnly = true)

    public com.gpstore.dto.response.DeliveryTrackingResponse getMyOrderTracking(Long orderId, Long callerCustomerId) {
        Delivery delivery = getOwnedDeliveryByOrderId(orderId, callerCustomerId)
                .orElseThrow(() -> new ResourceNotFoundException("No delivery found for this order yet"));
        return com.gpstore.dto.response.DeliveryTrackingResponse.from(delivery);
    }

    @Transactional
    public com.gpstore.dto.response.DeliveryResponse updateDeliveryStatus(Long deliveryId, String status, Long callerCustomerId, boolean isAdmin) {

        Delivery delivery = deliveryRepository.findById(deliveryId)
                .orElseThrow(() -> new ResourceNotFoundException("Delivery not found"));

        // Admins can update any delivery; a delivery partner can only update
        // ones actually assigned to them - this was previously missing
        // entirely, meaning any logged-in DELIVERY_BOY could update ANY
        // delivery by guessing an id, not just their own.
        if (!isAdmin) {
            DeliveryPartner caller = deliveryPartnerService.getByAccountIdOrThrow(callerCustomerId);
            Long assignedPartnerId = delivery.getBatch() != null && delivery.getBatch().getDeliveryPartner() != null
                    ? delivery.getBatch().getDeliveryPartner().getId()
                    : null;

            if (assignedPartnerId == null || !assignedPartnerId.equals(caller.getId())) {
                // Same "hide with generic not-found" pattern used for every
                // other ownership check in this codebase - don't reveal that
                // the delivery exists but belongs to someone else.
                throw new ResourceNotFoundException("Delivery not found");
            }
        }

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

                // This was never triggered anywhere before - a COD payment
                // record would stay stuck at COD_PENDING forever, even after
                // a real, successful delivery, since nothing else in the
                // app ever called PaymentService.completeCodPayment. Only
                // acts if it's actually a still-pending COD payment - a UPI
                // order's delivery completion must not be affected by this.
                paymentService.getPaymentByOrderId(order.getId()).ifPresent(payment -> {
                    if (com.gpstore.enums.PaymentMethod.COD.name().equals(payment.getPaymentMethod())
                                    && com.gpstore.enums.PaymentStatus.COD_PENDING.name().equals(payment.getPaymentStatus())) {
                        paymentService.completeCodPayment(order.getId());
                    }
                });
            }
        }

        return com.gpstore.dto.response.DeliveryResponse.from(deliveryRepository.save(delivery));
    }

    /** A delivery partner's own active assignments - resolved via their linked Customer account, never a client-supplied partner id. */
    @Transactional(readOnly = true)

    public List<com.gpstore.dto.response.MyDeliveryResponse> getMyAssignments(Long callerCustomerId) {
        DeliveryPartner partner = deliveryPartnerService.getByAccountIdOrThrow(callerCustomerId);
        return deliveryRepository.findActiveByPartnerId(partner.getId()).stream()
                .map(com.gpstore.dto.response.MyDeliveryResponse::from)
                .toList();
    }

    /**
     * Proactive half of the guarantee check: runs every 15 minutes, finds
     * deliveries still in transit that have already passed their promised ETA,
     * and flags them - so you find out a delivery is running late WHILE it's
     * happening, not only after a customer complains. No auto-refund/action;
     * this is deliberately just a review signal (see AuditLog + the
     * /api/deliveries/breached endpoint).
     */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelayString = "${delivery.late-flag-interval-ms:900000}", initialDelayString = "${delivery.late-flag-initial-delay-ms:60000}")
    @Transactional
    public void flagLateDeliveries() {
        // Bounded per run. This is a sweep over "everything that went late
        // since the last successful run", so its size grows with order
        // volume and with any gap in the scheduler running at all. An
        // unbounded version is fine every day until the one day it isn't -
        // and that is the day it holds a long transaction over a table
        // live deliveries are being written to. Anything not covered by
        // this run is picked up by the next one 15 minutes later.
        List<Delivery> lateDeliveries = deliveryRepository.findLateNotYetFlagged(
                LocalDateTime.now(),
                org.springframework.data.domain.PageRequest.of(0, LATE_FLAG_BATCH_SIZE));

        for (Delivery delivery : lateDeliveries) {
            delivery.setGuaranteeBreached(true);
            deliveryRepository.save(delivery);
            auditLogService.log("DELIVERY_GUARANTEE_BREACHED", "Delivery", delivery.getId(),
                    "still in transit past promised ETA of " + delivery.getEstimatedDeliveryTime());
        }
    }

    /** The manual-review list - every delivery that has ever missed its promised ETA. */
    @Transactional(readOnly = true)

    public List<com.gpstore.dto.response.DeliveryResponse> getBreachedDeliveries() {

        return deliveryRepository.findByGuaranteeBreachedTrueOrderByEstimatedDeliveryTimeDesc().stream()

                .map(com.gpstore.dto.response.DeliveryResponse::from)

                .collect(java.util.stream.Collectors.toList());
    }
}
