package com.gpstore.worker;

import com.gpstore.entity.DeliveryPartner;
import com.gpstore.entity.Order;
import com.gpstore.entity.OrderScanEvent;
import com.gpstore.exception.ResourceNotFoundException;
import com.gpstore.repository.DeliveryPartnerRepository;
import com.gpstore.repository.OrderRepository;
import com.gpstore.repository.OrderScanEventRepository;
import com.gpstore.service.AuditLogService;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The accountability half: who took which order, and the lever to change it.
 *
 * Admin only - see SecurityConfig. Reassigning an order overrides the
 * permanent territory rules for that one order, which is exactly the sort of
 * power that needs a name attached to every use of it.
 */
@RestController
@RequestMapping("/api/admin/worker")
public class WorkerAdminController {

    private final OrderRepository orderRepository;
    private final OrderScanEventRepository scanRepository;
    private final DeliveryPartnerRepository partnerRepository;
    private final WorkerScanService scanService;
    private final AuditLogService auditLogService;

    public WorkerAdminController(OrderRepository orderRepository,
                                 OrderScanEventRepository scanRepository,
                                 DeliveryPartnerRepository partnerRepository,
                                 WorkerScanService scanService,
                                 AuditLogService auditLogService) {
        this.orderRepository = orderRepository;
        this.scanRepository = scanRepository;
        this.partnerRepository = partnerRepository;
        this.scanService = scanService;
        this.auditLogService = auditLogService;
    }

    /**
     * Issues the QR token to print on the packing label.
     *
     * Returns the token itself, which is the one place it is ever readable -
     * the packing bench prints it and the worker's phone reads it off paper.
     * Re-issuing invalidates any label already printed, which is the recovery
     * path for a smudged sticker.
     */
    @PostMapping("/orders/{orderId}/qr-token")
    public Map<String, Object> issueQrToken(@PathVariable Long orderId) {
        String token = scanService.issueToken(orderId);
        return Map.of("orderId", orderId, "qrToken", token);
    }

    /**
     * "Who has GP125?" - the question this whole feature exists to answer.
     */
    @GetMapping("/orders/{orderId}/accountability")
    @Transactional(readOnly = true)
    public Map<String, Object> accountability(@PathVariable Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found: " + orderId));

        DeliveryPartner holder = order.getPackedByPartner();
        var subzone = order.getAddress() == null ? null : order.getAddress().getSubzone();

        Map<String, Object> body = new java.util.HashMap<>();
        body.put("orderId", order.getId());
        body.put("orderNumber", order.getOrderNumber());
        body.put("status", order.getOrderStatus());
        body.put("workerCode", WorkerScanService.workerCode(holder));
        body.put("workerName", holder == null ? null : holder.getName());
        body.put("zoneCode", WorkerScanService.zoneCodeOf(subzone));
        body.put("subzoneCode", WorkerScanService.subzoneCodeOf(subzone));
        body.put("packedAt", order.getPackedAt());
        body.put("assignedWorkerName", order.getAssignedWorkerPartner() == null
                ? null : order.getAssignedWorkerPartner().getName());
        body.put("history", history(orderId));
        return body;
    }

    /** Every scan attempt against this order, refusals included, newest first. */
    @GetMapping("/orders/{orderId}/scans")
    @Transactional(readOnly = true)
    public List<Map<String, Object>> scans(@PathVariable Long orderId) {
        return history(orderId);
    }

    /**
     * Reassigns an order to a named worker, overriding the territory rules for
     * this order only.
     *
     * WHAT THIS DOES NOT DO: it does not mark the order packed. The named
     * worker still has to scan it, because the scan is the moment somebody
     * physically picks up a carton and the record is worthless if it can be
     * created from a desk.
     *
     * If the order has already been scanned, reassigning it releases the token
     * so the new worker can scan it - otherwise the override would be
     * unusable exactly when it is most needed, which is after the wrong person
     * has taken something.
     */
    @PostMapping("/orders/{orderId}/reassign")
    @Transactional
    public Map<String, Object> reassign(@PathVariable Long orderId,
                                        @RequestBody Map<String, Long> body) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found: " + orderId));

        Long partnerId = body.get("partnerId");
        DeliveryPartner worker = partnerId == null ? null
                : partnerRepository.findById(partnerId).orElseThrow(
                        () -> new ResourceNotFoundException("Worker not found: " + partnerId));

        String previous = order.getPackedByPartner() == null
                ? "nobody" : order.getPackedByPartner().getName();

        order.setAssignedWorkerPartner(worker);
        if (order.getQrTokenUsedAt() != null) {
            order.setQrTokenUsedAt(null);
            order.setPackedByPartner(null);
            order.setPackedAt(null);
        }
        orderRepository.save(order);

        String note = worker == null
                ? "Assignment cleared; the territory rules decide again. Was held by " + previous + "."
                : "Reassigned to " + worker.getName() + " (was " + previous + "). The order must "
                        + "still be scanned by them - reassignment does not pack it.";

        // Written to BOTH trails on purpose. The audit log is the shop-wide
        // record of who did what; the scan history is what the accountability
        // screen shows, and an override that did not appear there would make
        // that screen quietly lie about how the order changed hands.
        auditLogService.log("ORDER_WORKER_REASSIGNED", "Order", orderId, note);

        OrderScanEvent event = new OrderScanEvent();
        event.setOrderId(order.getId());
        event.setOrderNumber(order.getOrderNumber());
        event.setPartnerId(worker == null ? null : worker.getId());
        event.setWorkerName(worker == null ? null : worker.getName());
        event.setAction("REASSIGNED");
        event.setOutcome("ADMIN_OVERRIDE");
        event.setReason(note);
        event.setPerformedByAdmin(true);
        scanRepository.save(event);

        return Map.of("orderId", orderId, "message", note);
    }

    private List<Map<String, Object>> history(Long orderId) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (OrderScanEvent e : scanRepository.findByOrderIdOrderByScannedAtDesc(orderId)) {
            Map<String, Object> row = new java.util.HashMap<>();
            row.put("workerCode", e.getPartnerId() == null ? null : "D" + e.getPartnerId());
            row.put("workerName", e.getWorkerName());
            row.put("action", e.getAction());
            row.put("outcome", e.getOutcome());
            row.put("reason", e.getReason());
            row.put("zoneCode", e.getZoneCode());
            row.put("subzoneCode", e.getSubzoneCode());
            row.put("scannedAt", e.getScannedAt());
            row.put("byAdmin", e.getPerformedByAdmin());
            rows.add(row);
        }
        return rows;
    }
}
