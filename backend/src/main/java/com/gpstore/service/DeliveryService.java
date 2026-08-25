package com.gpstore.service;

import com.gpstore.entity.AssignmentReason;
import com.gpstore.entity.Delivery;
import com.gpstore.entity.DeliveryBatch;
import com.gpstore.entity.DeliveryPartner;
import com.gpstore.entity.DeliverySubzone;
import com.gpstore.entity.Order;
import com.gpstore.delivery.DeliveryStatusTransitions;
import com.gpstore.enums.DeliveryStatus;
import com.gpstore.enums.OrderStatus;
import com.gpstore.exception.BadRequestException;
import com.gpstore.exception.ConflictException;
import com.gpstore.exception.ResourceNotFoundException;
import com.gpstore.repository.DeliveryPartnerRepository;
import com.gpstore.repository.DeliveryRepository;
import com.gpstore.repository.OrderRepository;
import com.gpstore.territory.TerritoryDispatchService;
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
    private final TerritoryDispatchService territoryDispatchService;
    private final com.gpstore.config.AfterCommitExecutor afterCommitExecutor;
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
            TerritoryDispatchService territoryDispatchService,
            com.gpstore.config.AfterCommitExecutor afterCommitExecutor,
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
        this.territoryDispatchService = territoryDispatchService;
        this.afterCommitExecutor = afterCommitExecutor;
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
     * Auto-assigns an order, preferring the rider who owns its permanent
     * territory.
     *
     * THE ORDER OF PREFERENCE, and why it is this way round. Before
     * territories existed this method picked whichever available partner had
     * the lightest load, anywhere in the service area. That is a reasonable
     * rule when riders are interchangeable, and they are not: a rider who
     * works Z7B every day knows which society gate actually opens, which lane
     * floods, and which tower has the lift out. So the territory's own rider
     * is asked first, and only when they are absent or already at capacity
     * does TerritoryDispatchService walk outward - named backup, declared
     * neighbouring territory, same main zone, neighbouring main zone.
     *
     * WHAT HAPPENS WHEN THE MAP CANNOT HELP. Until at least one subzone has a
     * drawable outline ({@code mappedTerritoryCount() == 0}), the territory
     * ladder is not consulted at all. An address in no drawn territory, or a
     * ladder that finds nobody geographically suitable, falls back to the
     * old least-loaded pick rather than leaving the order unassigned. The
     * old least-loaded pick rather than leaving the order unassigned. The
     * order is already placed and usually already paid; refusing to dispatch
     * it would be a worse answer than dispatching it imperfectly. It is
     * recorded as FALLBACK so a rising count is visible as what it is - a hole
     * in the map, not a dispatch tuning problem.
     *
     * VEHICLE TYPE STILL APPLIES, but only on the fallback path. A bulk order
     * (>= bulkOrderItemThreshold items) wants a PICKUP rather than a bike.
     * Deliberately NOT used to reject the territory's own rider: sending
     * someone who does not know the streets because the local rider is on a
     * bike trades a real, everyday advantage for a capacity guess, and a rider
     * who cannot physically carry a load will say so far more reliably than a
     * threshold in a config file.
     */
    @Transactional
    public com.gpstore.dto.response.DeliveryResponse autoAssignDelivery(Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));

        DeliverySubzone subzone = order.getAddress() == null ? null : order.getAddress().getSubzone();
        Double lat = order.getAddress() == null ? null : order.getAddress().getLatitude();
        Double lng = order.getAddress() == null ? null : order.getAddress().getLongitude();

        TerritoryDispatchService.DispatchDecision decision =
                territoryDispatchService.chooseFor(subzone, lat, lng);

        if (decision.hasPartner()) {
            return assignDelivery(orderId, decision.partner().getId(), decision);
        }

        int totalItemCount = order.getOrderItems() == null ? 0 :
                order.getOrderItems().stream().mapToInt(item -> item.getQuantity() == null ? 0 : item.getQuantity()).sum();

        String preferredVehicleType = totalItemCount >= bulkOrderItemThreshold ? "PICKUP" : "BIKE";

        DeliveryPartner partner = deliveryPartnerService.getLeastLoadedAvailablePartner(preferredVehicleType);

        // Recorded, not swallowed. Every one of these is an order that went
        // out without local knowledge, and the reason is worth reading.
        auditLogService.log("DELIVERY_TERRITORY_FALLBACK", "Order", orderId, decision.explanation());

        return assignDelivery(orderId, partner.getId(), decision);
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
        // A hand assignment by an administrator. It is still recorded against
        // the order's territory - which territory a delivery happened in is a
        // fact about the delivery, not about how the rider was chosen - but
        // the reason says plainly that no ladder was walked.
        return assignDelivery(orderId, deliveryPartnerId, null);
    }

    @Transactional
    public com.gpstore.dto.response.DeliveryResponse assignDelivery(
            Long orderId, Long deliveryPartnerId,
            TerritoryDispatchService.DispatchDecision decision) {

        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));

        if (deliveryRepository.findByOrderId(orderId).isPresent()) {
            throw new BadRequestException("This order already has a delivery assigned");
        }

        DeliveryPartner partner = deliveryPartnerRepository.findById(deliveryPartnerId)
                .orElseThrow(() -> new ResourceNotFoundException("Delivery partner not found"));

        DeliverySubzone subzone = order.getAddress() == null ? null : order.getAddress().getSubzone();

        // Batching key. A subzone is a row in a table; the old area string was
        // whatever the customer typed, matched with =, so "Sector 12",
        // "sector 12" and "Sector-12" opened three batches for one
        // neighbourhood. The string is still passed for addresses that have
        // no territory yet, because some grouping beats none.
        String area = subzone != null
                ? subzone.getCode()
                : (order.getAddress() != null && order.getAddress().getArea() != null
                        ? order.getAddress().getArea()
                        : "UNASSIGNED");

        DeliveryBatch batch = deliveryBatchService.getOrCreateOpenBatch(deliveryPartnerId, area, subzone);

        Double lat = order.getAddress() != null ? order.getAddress().getLatitude() : null;
        Double lng = order.getAddress() != null ? order.getAddress().getLongitude() : null;

        double distanceKm = deliveryEstimateService.distanceFromStoreKm(lat, lng);
        int estimateMinutes = deliveryEstimateService.estimateMinutes(lat, lng);

        Delivery delivery = new Delivery();
        delivery.setOrder(order);
        delivery.setBatch(batch);
        delivery.setSubzone(subzone);
        delivery.setAssignmentReason(
                decision == null ? AssignmentReason.PRIMARY : decision.reason());
        delivery.setDistanceKm(Double.isNaN(distanceKm) ? null : distanceKm);
        delivery.setDeliveryStatus("ASSIGNED");
        delivery.setDeliveryPersonName(partner.getName());
        delivery.setDeliveryPersonPhone(partner.getMobile());
        delivery.setAssignedAt(LocalDateTime.now());
        delivery.setEstimatedDeliveryTime(LocalDateTime.now().plusMinutes(estimateMinutes));
        delivery.setActive(true);

        Delivery saved = deliveryRepository.save(delivery);

        // FCM must not hold this transaction's pool connection for a Google
        // round trip. Touch lazy fields now, then notify after commit.
        touchPartnerForPush(partner, saved);
        afterCommitExecutor.runAfterCommit("Partner assignment notification",
                saved.getId(),
                () -> notificationService.notifyPartnerNewAssignment(partner, saved));

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
     * The same lookup, mapped while the session is still open.
     *
     * DeliveryResponse names the order, and Delivery.order is lazy - so the
     * controller mapping the entity itself only worked because
     * open-session-in-view was holding a database connection open for the
     * whole request. With that off (see spring.jpa.open-in-view) the mapping
     * has to happen inside the transaction, which is where it belonged: the
     * controller's job is to answer the request, not to run queries while
     * Jackson writes the response.
     */
    @Transactional(readOnly = true)
    public Optional<com.gpstore.dto.response.DeliveryResponse> getOwnedDeliveryResponse(
            Long orderId, Long callerCustomerId) {
        return getOwnedDeliveryByOrderId(orderId, callerCustomerId)
                .map(com.gpstore.dto.response.DeliveryResponse::from);
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

    /**
     * Moves a delivery to a new status, or refuses.
     *
     * WHAT THIS USED TO DO, because the fix only makes sense next to it:
     *
     *     delivery.setDeliveryStatus(status);
     *
     * where `status` was a @RequestParam string from a phone. Nothing checked
     * it against anything. Three separate problems, in rising order of cost:
     *
     *   1. "BANANA" was a valid delivery status, and so was "delivered " with
     *      a trailing space - which then failed every equalsIgnoreCase branch
     *      below while looking correct in the admin list.
     *   2. The DELIVERED branch fires on the string alone, so a delivery
     *      partner could mark an order delivered straight from ASSIGNED -
     *      while it was still on the packing bench. That stamps deliveredAt,
     *      tells the customer it arrived, and completes the COD payment for
     *      cash nobody collected.
     *   3. Nothing recorded that any of it had happened.
     *
     * Now: the value is parsed, the move is checked against
     * DeliveryStatusTransitions, and an illegal move is refused with a message
     * naming what IS allowed. The authorization check below is unchanged and
     * still runs first - being allowed to touch this delivery and being
     * allowed to make this particular move are two different questions.
     */
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

        // ---- the status itself ------------------------------------------
        DeliveryStatus target = DeliveryStatus.parse(status)
                .orElseThrow(() -> new BadRequestException(
                        "\"" + status + "\" is not a delivery status. Valid values: "
                                + String.join(", ",
                                        java.util.Arrays.stream(DeliveryStatus.values())
                                                .map(Enum::name).toList()) + "."));

        DeliveryStatus current = DeliveryStatus.parse(delivery.getDeliveryStatus()).orElse(null);

        if (!DeliveryStatusTransitions.isAllowed(current, target)) {
            throw new ConflictException(DeliveryStatusTransitions.refusalMessage(current, target));
        }

        // Re-asserting the same state does nothing at all. Returning early
        // rather than falling through matters: the branches below stamp
        // deliveredAt and complete COD payments, and a retried request must
        // not do either of those a second time.
        if (current == target) {
            return com.gpstore.dto.response.DeliveryResponse.from(delivery);
        }

        delivery.setDeliveryStatus(target.name());

        // Who moved it, when, and to what. The admin accountability view is
        // built from these, and without the log a delivery's history is just
        // its current row - which says nothing about how it got there.
        auditLogService.log("DELIVERY_STATUS_" + target.name(), "Delivery", delivery.getId(),
                "from=" + (current == null ? "NONE" : current.name())
                        + ", to=" + target.name()
                        + ", by=" + (isAdmin ? "admin" : "worker")
                        + " account " + callerCustomerId);

        Order order = delivery.getOrder();

        if (target == DeliveryStatus.OUT_FOR_DELIVERY && order != null) {
            order.setOrderStatus(OrderStatus.OUT_FOR_DELIVERY);
            orderRepository.save(order);
            touchOrderForPush(order);
            afterCommitExecutor.runAfterCommit("Out-for-delivery notification", order.getId(),
                    () -> notificationService.notifyOrderStatusChange(order, OrderStatus.OUT_FOR_DELIVERY));
        }

        if (target == DeliveryStatus.DELIVERED) {

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
                touchOrderForPush(order);
                afterCommitExecutor.runAfterCommit("Delivered notification", order.getId(),
                        () -> notificationService.notifyOrderStatusChange(order, OrderStatus.DELIVERED));

                // COD completion stays IN this transaction. It is payment
                // state, not a push, and must commit with DELIVERED.
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

    private static void touchOrderForPush(Order order) {
        if (order == null) {
            return;
        }
        order.getOrderNumber();
        if (order.getCustomer() != null) {
            order.getCustomer().getFcmToken();
        }
    }

    private static void touchPartnerForPush(DeliveryPartner partner, Delivery delivery) {
        if (partner != null && partner.getAccount() != null) {
            partner.getAccount().getFcmToken();
        }
        if (delivery != null) {
            touchOrderForPush(delivery.getOrder());
        }
    }
}
