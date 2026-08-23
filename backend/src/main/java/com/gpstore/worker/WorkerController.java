package com.gpstore.worker;

import com.gpstore.entity.DeliveryPartner;
import com.gpstore.entity.DeliverySubzone;
import com.gpstore.entity.OrderScanEvent;
import com.gpstore.exception.BadRequestException;
import com.gpstore.repository.DeliveryPartnerRepository;
import com.gpstore.repository.DeliveryRepository;
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
 * THERE IS NO LOGIN HERE ON PURPOSE. The worker signs in through the existing
 * /api/auth endpoints with the DELIVERY_BOY account an administrator created
 * for them, and this controller reads that identity from the JWT. Adding a
 * second login path would mean a second place where credentials are checked
 * and a second place to get it wrong, for no gain.
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
    private final CurrentUser currentUser;

    public WorkerController(WorkerScanService scanService,
                            DeliveryPartnerRepository partnerRepository,
                            OrderScanEventRepository scanRepository,
                            DeliveryRepository deliveryRepository,
                            CurrentUser currentUser) {
        this.scanService = scanService;
        this.partnerRepository = partnerRepository;
        this.scanRepository = scanRepository;
        this.deliveryRepository = deliveryRepository;
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
        return scanService.packScan(currentUser.customerId(), request.qrToken(), request.clientRequestId());
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

    private DeliveryPartner requireWorker() {
        return partnerRepository.findByAccountId(currentUser.customerId())
                .orElseThrow(() -> new BadRequestException(
                        "This login is not linked to a worker record. Ask an administrator to link it."));
    }
}
