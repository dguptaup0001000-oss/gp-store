package com.gpstore.worker;

import com.gpstore.entity.DeliveryPartner;
import com.gpstore.entity.DeliverySubzone;
import com.gpstore.entity.Order;
import com.gpstore.entity.OrderScanEvent;
import com.gpstore.exception.BadRequestException;
import com.gpstore.exception.ResourceNotFoundException;
import com.gpstore.repository.DeliveryPartnerRepository;
import com.gpstore.repository.DeliveryRepository;
import com.gpstore.repository.OrderRepository;
import com.gpstore.repository.OrderScanEventRepository;
import com.gpstore.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The delivery worker's app talks to exactly these routes.
 *
 * SIGN-IN LIVES NEXT DOOR, in WorkerAuthController. A worker's credentials are
 * on their roster row, not on a Customer account, so their token carries the
 * roster id and this controller reads it straight from the JWT. That is what
 * replaced the old arrangement, where a worker signed in as a customer and
 * then had to be translated back through an account link that could - and
 * did - come back empty on a rider who had signed in perfectly well.
 *
 * NOTHING HERE READS AN IDENTITY FROM THE REQUEST. Not a worker id, not a
 * zone, not a subzone, not an order status. Every one of those is looked up
 * from the authenticated account, because a client that can name itself is a
 * client that can name somebody else.
 */
@RestController
@RequestMapping("/api/worker")
public class WorkerController {

    /** The scan request: a token and a retry id. Nothing else is trusted. */
    public record PackScanRequest(
            @NotBlank(message = "Scan a QR code first.")
            @Size(max = 64, message = "That is not a GP-STORE order label.")
            String qrToken,

            /**
             * The app's own id for this attempt, so a retry over a bad
             * connection replays instead of scanning twice. Optional - a
             * missing one simply forfeits that protection rather than failing
             * the scan, because a worker at the counter should not be blocked
             * by a client-side detail.
             */
            @Size(max = 80)
            String clientRequestId) {
    }

    private final WorkerScanService scanService;
    private final DeliveryPartnerRepository partnerRepository;
    private final OrderScanEventRepository scanRepository;
    private final DeliveryRepository deliveryRepository;
    private final OrderRepository orderRepository;
    private final com.gpstore.service.DeliveryService deliveryService;
    private final CurrentUser currentUser;

    public WorkerController(WorkerScanService scanService,
                            DeliveryPartnerRepository partnerRepository,
                            OrderScanEventRepository scanRepository,
                            DeliveryRepository deliveryRepository,
                            OrderRepository orderRepository,
                            com.gpstore.service.DeliveryService deliveryService,
                            CurrentUser currentUser) {
        this.scanService = scanService;
        this.partnerRepository = partnerRepository;
        this.scanRepository = scanRepository;
        this.deliveryRepository = deliveryRepository;
        this.orderRepository = orderRepository;
        this.deliveryService = deliveryService;
        this.currentUser = currentUser;
    }

    /**
     * Everything the home screen shows, in one call.
     *
     * One call rather than four because this screen opens on a cheap phone on
     * a rural connection, and the worker's next action is to scan - every
     * extra round trip is time spent looking at a spinner instead.
     */
    @GetMapping("/me")
    @Transactional(readOnly = true)
    public Map<String, Object> me() {
        DeliveryPartner worker = requireWorker();
        DeliverySubzone territory = scanService.territoryOf(worker).orElse(null);

        Map<String, Object> body = new java.util.HashMap<>();
        body.put("workerCode", WorkerScanService.workerCode(worker));
        body.put("name", worker.getName());
        body.put("mobile", worker.getMobile());
        body.put("zoneCode", WorkerScanService.zoneCodeOf(territory));
        body.put("subzoneCode", WorkerScanService.subzoneCodeOf(territory));
        body.put("subzoneName", territory == null ? null : territory.getName());
        body.put("status", statusOf(worker));
        body.put("todaysOrders", scanRepository.countAcceptedPacksSince(
                worker.getId(), LocalDate.now().atStartOfDay()));

        // THE ACTIVE TASKS RIDE ALONG, which is the whole reason this endpoint
        // says "everything the home screen shows, in one call" and means it.
        // Fetching them separately would be a second round trip on the screen
        // that opens most often, on the worst connection in the business - and
        // the home screen has nothing to draw until both have answered anyway,
        // so two requests is just the slower of the two arriving later.
        //
        // Same rows the delivery app's own /my-assignments returns, built by
        // the same code, so the ownership check has one home and cannot drift.
        body.put("activeTasks", deliveryService.getMyAssignments(worker.getId()));

        return body;
    }

    /**
     * What this worker has taken today, newest first.
     *
     * Deliberately their own scan history rather than a work queue. These are
     * shop employees who pack and deliver as the day requires; a list of orders
     * "assigned" to them ahead of time would be a promise the shop floor does
     * not make.
     */
    @GetMapping("/orders")
    @Transactional(readOnly = true)
    public List<Map<String, Object>> myOrders(@RequestParam(defaultValue = "50") int limit) {
        DeliveryPartner worker = requireWorker();

        List<Map<String, Object>> rows = new ArrayList<>();
        for (OrderScanEvent event : scanRepository.findByPartnerSince(
                worker.getId(), LocalDate.now().atStartOfDay(),
                PageRequest.of(0, Math.min(Math.max(limit, 1), 200)))) {
            Map<String, Object> row = new java.util.HashMap<>();
            row.put("orderNumber", event.getOrderNumber());
            row.put("action", event.getAction());
            row.put("outcome", event.getOutcome());
            row.put("reason", event.getReason());
            row.put("subzoneCode", event.getSubzoneCode());
            row.put("scannedAt", event.getScannedAt());
            rows.add(row);
        }
        return rows;
    }

    /**
     * One order, reopened.
     *
     * The scan response already carries this view, so the normal flow never
     * calls it - it exists for the second look: the worker backed out of the
     * screen, the app was killed on the way to the bike, they tapped an active
     * task from the home list. Re-scanning a label to see it again would work
     * too, and would be a worse app.
     *
     * AUTHORISATION IS NOT "IS A WORKER". A logged-in worker asking for an
     * arbitrary order number must not get a customer's name, phone and address
     * back, so the same two questions the scan asks are asked again here:
     * did THIS worker pack it, or is it assigned to them? Anything else is a
     * plain not-found - never "exists but not yours", which would turn this
     * into a way to confirm order numbers.
     */
    @GetMapping("/orders/{orderId}")
    @Transactional(readOnly = true)
    public WorkerOrderView order(@PathVariable Long orderId) {
        DeliveryPartner worker = requireWorker();

        // Keyed on the id, not the order number, because every response the
        // worker app already holds - the scan result and the active-task row -
        // carries the id. Looking up by number would need a new index on a
        // column nothing else queries by.
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));

        boolean packedByThisWorker = order.getPackedByPartner() != null
                && order.getPackedByPartner().getId().equals(worker.getId());

        boolean assignedToThisWorker = deliveryRepository.findByOrderId(order.getId())
                .map(delivery -> delivery.getBatch() != null
                        && delivery.getBatch().getDeliveryPartner() != null
                        && delivery.getBatch().getDeliveryPartner().getId().equals(worker.getId()))
                .orElse(false);

        if (!packedByThisWorker && !assignedToThisWorker) {
            throw new ResourceNotFoundException("Order not found");
        }

        return scanService.viewOf(order);
    }

    /**
     * The scan. One route, one job.
     *
     * Always 200 with an outcome in the body, including for a refusal, and
     * that is a deliberate choice rather than sloppiness about status codes. A
     * refused scan is a normal, expected event at a packing bench - the label
     * is from a cancelled order, or a colleague already took it - and the
     * worker needs to READ WHY. Non-2xx responses get funnelled into generic
     * "something went wrong" handling by every HTTP client ever written, which
     * would replace the one sentence that helps with one that does not.
     * Genuine faults - no worker record, malformed request - still throw.
     */
    @PostMapping("/scans/pack")
    public WorkerScanService.ScanResult packScan(@Valid @RequestBody PackScanRequest request) {
        return scanService.packScan(requireWorker().getId(), request.qrToken(), request.clientRequestId());
    }

    /**
     * Mints the packing label for one order: a QR token to scan, and a short
     * code to type when the camera will not.
     *
     * ADMIN ONLY, and that restriction is the feature rather than a detail
     * around it. SecurityConfig pins this path to DELIVERY_MANAGE, above the
     * rule that lets a delivery worker reach the rest of /api/worker. If a
     * worker could mint a label they could mint their own credential and
     * claim any order in the shop without ever touching the carton - which
     * would make both the QR and the typed code decorative.
     *
     * RE-ISSUING IS THE RECOVERY PATH for a smudged sticker or a carton that
     * went in the bin wearing its label. It invalidates whatever was printed
     * before, because the old strings simply stop matching.
     */
    @PostMapping("/orders/{orderId}/label")
    public Map<String, Object> issueLabel(@PathVariable Long orderId) {
        return scanService.issueLabel(orderId);
    }

    /**
     * AVAILABLE, ON_DELIVERY or OFFLINE - derived, never stored.
     *
     * A third status column would be a third thing to keep in step with
     * `active` and `available`, and the first time it drifted the roster would
     * disagree with itself. Derivation cannot drift.
     */
    @GetMapping("/status")
    @Transactional(readOnly = true)
    public Map<String, Object> status() {
        DeliveryPartner worker = requireWorker();
        return Map.of("status", statusOf(worker),
                "available", Boolean.TRUE.equals(worker.getAvailable()),
                "active", Boolean.TRUE.equals(worker.getActive()));
    }

    /**
     * A worker going on or off shift.
     *
     * They can set their own availability but NOT their own active flag -
     * that is the roster, and it belongs to whoever runs the shop. A worker
     * who could deactivate themselves could also quietly remove themselves
     * from every territory rule.
     */
    @PostMapping("/status")
    @Transactional
    public Map<String, Object> setStatus(@RequestBody Map<String, Object> body) {
        DeliveryPartner worker = requireWorker();
        Object available = body.get("available");
        if (!(available instanceof Boolean)) {
            throw new BadRequestException("Send {\"available\": true} or {\"available\": false}.");
        }
        worker.setAvailable((Boolean) available);
        partnerRepository.save(worker);
        return Map.of("status", statusOf(worker));
    }

    private String statusOf(DeliveryPartner worker) {
        if (!Boolean.TRUE.equals(worker.getActive()) || !Boolean.TRUE.equals(worker.getAvailable())) {
            return "OFFLINE";
        }
        return deliveryRepository.countActiveByPartnerId(worker.getId()) > 0
                ? "ON_DELIVERY" : "AVAILABLE";
    }

    /**
     * The roster row behind this request.
     *
     * IT COMES FROM THE TOKEN, not from a lookup that can come back empty.
     * A worker session carries its own roster id, so the old failure - sign
     * in successfully, then be told "this login is not linked to a worker
     * record" with no way to fix it from inside either app - cannot happen
     * any more. The only remaining case is a staff account visiting the
     * worker API, which is a different sentence because it has a different
     * fix.
     */
    private DeliveryPartner requireWorker() {
        Long workerId = currentUser.get().getWorkerId();
        if (workerId == null) {
            throw new BadRequestException(
                    "Sign in with a worker login to use the worker app. Staff accounts "
                            + "manage the roster from the admin app instead.");
        }
        return partnerRepository.findById(workerId)
                .filter(worker -> worker.getDeletedAt() == null)
                .orElseThrow(() -> new BadRequestException(
                        "This worker account is no longer on the roster."));
    }
}
