package com.gpstore.worker;

import com.gpstore.entity.DeliveryPartner;
import com.gpstore.entity.DeliverySubzone;
import com.gpstore.entity.Order;
import com.gpstore.entity.OrderScanEvent;
import com.gpstore.entity.SubzoneBackupPartner;
import com.gpstore.delivery.DeliveryStatusTransitions;
import com.gpstore.enums.DeliveryStatus;
import com.gpstore.enums.OrderStatus;
import com.gpstore.exception.BadRequestException;
import com.gpstore.exception.ResourceNotFoundException;
import com.gpstore.repository.DeliveryPartnerRepository;
import com.gpstore.repository.DeliverySubzoneRepository;
import com.gpstore.repository.OrderRepository;
import com.gpstore.repository.OrderScanEventRepository;
import com.gpstore.repository.SubzoneBackupPartnerRepository;
import com.gpstore.service.AuditLogService;
import com.gpstore.service.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/**
 * A worker scans the QR on a packed order; the backend decides whether that is
 * allowed and records who took it.
 *
 * THE ONE RULE THIS FILE EXISTS TO ENFORCE: nothing the phone says about
 * identity or permission is believed. The request carries a token and a
 * request id. Who is scanning comes from the JWT, which worker record that
 * maps to comes from the database, which territory they own comes from the
 * database, and which order the token refers to comes from the database. A
 * worker id, zone, subzone or order status sent by a client is not read
 * anywhere in this class, because the moment one is, the whole scheme is worth
 * nothing - anybody with a login could claim to be anybody.
 *
 * WHY THE SCAN LOOKS UP BY TOKEN AND NEVER BY ORDER ID. An endpoint that
 * accepts an order id is an endpoint whose ids can be walked. The token is
 * random, single use, and the only handle the app has.
 *
 * WHAT THE CUSTOMER IS TOLD, and the reason it is so carefully limited: these
 * workers are shop employees who also deliver. A scan happens at the counter,
 * with the order still in the shop. "Ready for delivery", "picked up", "on the
 * way" would all be promises about a journey that has not started, and a
 * customer who reads one starts waiting at the door. They are told the order is
 * packed, which is exactly what just became true.
 */
@Service
public class WorkerScanService {

    private static final Logger log = LoggerFactory.getLogger(WorkerScanService.class);

    /** 32 URL-safe characters of randomness - not a sequence, not derived from the order. */
    private static final int TOKEN_BYTES = 24;
    private static final SecureRandom RANDOM = new SecureRandom();

    /** No O/0, no I/1/L - the pairs people confuse on a smudged sticker. */
    private static final String PACK_CODE_ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ";
    private static final int PACK_CODE_LENGTH = 8;

    /**
     * How many wrong codes a worker may type before the shop is told.
     *
     * TYPING IS A BRUTE-FORCE SURFACE THAT SCANNING IS NOT. A camera can only
     * offer what is physically in front of it; a keyboard can offer anything.
     * Without a ceiling, the code path would be the weakest way into an order
     * rather than an equal one.
     *
     * Ten in an hour is far above honest fumbling - a worker reading a real
     * sticker gets it in one or two - and far below the millions a guess would
     * need against a 6.6e11 space.
     */
    private static final int MAX_WRONG_CODES_PER_HOUR = 10;

    /**
     * The states a packed-order scan makes sense from.
     *
     * PENDING_CONFIRMATION is excluded because an unconfirmed order should not
     * be packed at all; the delivered/dispatched states because the order has
     * already gone; CANCELLED because a label on a cancelled order is exactly
     * the mistake this should catch at the counter rather than at the door.
     * READY_TO_DISPATCH is allowed: it is the older name for the same
     * operational moment and live orders still hold it.
     */
    private static final List<OrderStatus> SCANNABLE = List.of(
            OrderStatus.CONFIRMED, OrderStatus.PACKING, OrderStatus.READY_TO_DISPATCH);

    /** The outcome of an attempt, as recorded and as shown to the worker. */
    public record ScanResult(boolean accepted,
                             String outcome,
                             String message,
                             String orderNumber,
                             String workerName,
                             String workerCode,
                             String zoneCode,
                             String subzoneCode,
                             LocalDateTime scannedAt,
                             boolean replayed,

                             /**
                              * The order itself, on an accepted scan. Null on
                              * a refusal and on a replay.
                              *
                              * CARRIED HERE TO SAVE A ROUND TRIP, which on a
                              * village 4G connection is the difference between
                              * a worker reading a packing list and a worker
                              * watching a spinner. The alternative shape -
                              * scan, then fetch the order - is two requests
                              * for one action, and the second one is on the
                              * critical path of every single scan of the day.
                              *
                              * Null on a refusal because a refused scan means
                              * this worker may not see this order; sending its
                              * contents anyway would hand over exactly what
                              * the refusal withheld. Null on a replay because
                              * a replay is answered from the recorded event,
                              * not from a fresh read of the order.
                              */
                             WorkerOrderView order) {
    }

    private final OrderRepository orderRepository;
    private final OrderScanEventRepository scanRepository;
    private final DeliveryPartnerRepository partnerRepository;
    private final com.gpstore.repository.DeliveryRepository deliveryRepository;
    private final com.gpstore.repository.PaymentRepository paymentRepository;
    private final DeliverySubzoneRepository subzoneRepository;
    private final SubzoneBackupPartnerRepository backupRepository;
    private final NotificationService notificationService;
    private final AuditLogService auditLogService;
    private final com.gpstore.territory.TerritoryResolver territoryResolver;
    private final com.gpstore.config.AfterCommitExecutor afterCommitExecutor;

    public WorkerScanService(com.gpstore.territory.TerritoryResolver territoryResolver,
                             OrderRepository orderRepository,
                             OrderScanEventRepository scanRepository,
                             DeliveryPartnerRepository partnerRepository,
                             com.gpstore.repository.DeliveryRepository deliveryRepository,
                             com.gpstore.repository.PaymentRepository paymentRepository,
                             DeliverySubzoneRepository subzoneRepository,
                             SubzoneBackupPartnerRepository backupRepository,
                             NotificationService notificationService,
                             AuditLogService auditLogService,
                             com.gpstore.config.AfterCommitExecutor afterCommitExecutor) {
        this.territoryResolver = territoryResolver;
        this.orderRepository = orderRepository;
        this.scanRepository = scanRepository;
        this.partnerRepository = partnerRepository;
        this.deliveryRepository = deliveryRepository;
        this.paymentRepository = paymentRepository;
        this.subzoneRepository = subzoneRepository;
        this.backupRepository = backupRepository;
        this.notificationService = notificationService;
        this.auditLogService = auditLogService;
        this.afterCommitExecutor = afterCommitExecutor;
    }

    // ------------------------------------------------------------------ token

    /**
     * Issues (or re-issues) the QR token for an order. Admin only - this is
     * what the packing bench prints onto the label.
     *
     * Re-issuing invalidates whatever was printed before, because the old
     * string simply stops matching. That is the recovery path for a smudged
     * label or a carton that went in the bin with its sticker still on.
     */
    @Transactional
    public String issueToken(Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found: " + orderId));

        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        order.setQrToken(token);
        order.setPackCode(freshPackCode());
        order.setQrTokenIssuedAt(LocalDateTime.now());
        // A reprint un-uses the token. Without this, reprinting a label for an
        // order whose scan was recorded in error would produce a sticker that
        // can never be scanned, and the only fix would be a database edit.
        order.setQrTokenUsedAt(null);
        orderRepository.save(order);

        auditLogService.log("ORDER_QR_ISSUED", "Order", orderId,
                "QR token and pack code issued for packing label. Any previously printed label "
                        + "for this order no longer scans and its code no longer works.");
        return token;
    }

    /**
     * Both halves of the label, for the bench that prints it.
     *
     * Returned together because they are one credential presented two ways,
     * and a label carrying only one of them is a label that fails the moment
     * the camera does.
     */
    @Transactional
    public java.util.Map<String, Object> issueLabel(Long orderId) {
        String token = issueToken(orderId);
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found: " + orderId));
        java.util.Map<String, Object> label = new java.util.HashMap<>();
        label.put("orderNumber", order.getOrderNumber());
        label.put("qrToken", token);
        label.put("packCode", order.getPackCode());
        return label;
    }

    /**
     * The typeable half of the label.
     *
     * NO O/0 AND NO I/1/L. Those are the pairs people actually confuse
     * reading a smudged sticker in a dark storeroom, and a code that is
     * ambiguous to read is a code that produces failed attempts - which this
     * design then counts against the worker as if they were guessing.
     *
     * Eight characters from thirty symbols is about 6.6e11 combinations. That
     * matters less than the attempt limit in the entry path, but a code short
     * enough to brute force would make that limit the only defence rather
     * than the second one.
     *
     * COLLISION IS HANDLED BY RETRYING, not by hoping. The unique index means
     * a collision would otherwise surface as a failed insert at the packing
     * bench, which is the worst possible moment.
     */
    private String freshPackCode() {
        for (int attempt = 0; attempt < 5; attempt++) {
            StringBuilder code = new StringBuilder(PACK_CODE_LENGTH);
            for (int i = 0; i < PACK_CODE_LENGTH; i++) {
                code.append(PACK_CODE_ALPHABET.charAt(RANDOM.nextInt(PACK_CODE_ALPHABET.length())));
            }
            String candidate = code.toString();
            if (orderRepository.findByPackCode(candidate).isEmpty()) {
                return candidate;
            }
        }
        // Five collisions in a row against a 6.6e11 space is not bad luck, it
        // is a broken random source. Refusing to print a label beats printing
        // one that might name somebody else's order.
        throw new IllegalStateException("Could not generate a unique pack code.");
    }

    /**
     * What a worker typed, turned into what is stored.
     *
     * People type what they see, and what they see is grouped and lowercase
     * on some keyboards: "k7m4-p2qx", "K7M4 P2QX". All of those are the same
     * label. Normalising here rather than asking the worker to be careful is
     * the difference between a code that works and one that gets blamed on
     * the app.
     */
    static String normalisePackCode(String typed) {
        if (typed == null) {
            return null;
        }
        StringBuilder cleaned = new StringBuilder(typed.length());
        for (char c : typed.toUpperCase(java.util.Locale.ROOT).toCharArray()) {
            if (Character.isLetterOrDigit(c)) {
                cleaned.append(c);
            }
        }
        return cleaned.length() == 0 ? null : cleaned.toString();
    }

    // ------------------------------------------------------------------- scan

    /**
     * The whole flow: identify, validate, authorise, record, notify.
     *
     * EVERY REJECTION IS RECORDED, not just thrown. A worker being told no at
     * the counter is the event an administrator most needs to see afterwards,
     * and the reason distinguishes a wrong territory from a taken order from a
     * cancelled one.
     *
     * @param workerId the roster row from the worker's own token - never from
     *                 the request body. A worker session carries this
     *                 directly now, so there is no account link to translate
     *                 through and no way for it to be missing.
     */
    @Transactional
    public ScanResult packScan(Long workerId, String qrToken, String clientRequestId) {
        DeliveryPartner worker = partnerRepository.findById(workerId)
                .filter(candidate -> candidate.getDeletedAt() == null)
                .orElseThrow(() -> new BadRequestException(
                        "This worker account is no longer on the roster."));

        // ---- Replay before anything else -------------------------------
        // A retry must not be able to fail differently from the attempt it is
        // retrying. Checking here, before the order is even looked up, means a
        // second tap returns the first answer whatever has changed in between.
        if (clientRequestId != null && !clientRequestId.isBlank()) {
            Optional<OrderScanEvent> previous =
                    scanRepository.findByPartnerIdAndClientRequestId(worker.getId(), clientRequestId);
            if (previous.isPresent()) {
                return replay(previous.get(), worker);
            }
        }

        if (!Boolean.TRUE.equals(worker.getActive())) {
            return reject(null, worker, clientRequestId, "WORKER_INACTIVE",
                    "This worker account is not active. Ask an administrator.");
        }

        // ---- The label names the order. Nothing else does. --------------
        // FOR UPDATE: two workers can scan the same carton in the same
        // second. An unlocked read lets both see qrTokenUsedAt == null and
        // both write packedByPartner. The second then wins silently.
        //
        // TWO WAYS TO PRESENT THE SAME LABEL. The scanned token and the typed
        // code are both random and both consumed by the same flag, so which
        // one a worker used changes nothing about what they are allowed to do.
        // The order number is deliberately NOT one of them - it is sequential
        // and printed on the customer's invoice, so accepting it would let a
        // worker claim an order they had never held.
        Optional<Order> found = orderRepository.findByQrTokenForUpdate(qrToken);
        boolean typed = false;

        if (found.isEmpty()) {
            String code = normalisePackCode(qrToken);
            if (code != null) {
                found = orderRepository.findByPackCodeForUpdate(code);
                typed = found.isPresent();
            }
        }

        if (found.isEmpty()) {
            // COUNTED, because a keyboard can offer anything and a camera
            // cannot. A worker fumbling a smudged sticker is normal; a run of
            // wrong codes is somebody walking the space, and the shop should
            // find that in the scan history rather than never.
            long wrongLately = scanRepository.countRejectionsSince(
                    worker.getId(), "UNKNOWN_TOKEN", LocalDateTime.now().minusHours(1));
            if (wrongLately >= MAX_WRONG_CODES_PER_HOUR) {
                return reject(null, worker, clientRequestId, "TOO_MANY_WRONG_CODES",
                        "Too many wrong codes. Ask an administrator to check the label "
                                + "or reprint it.");
            }
            return reject(null, worker, clientRequestId, "UNKNOWN_TOKEN",
                    "That code does not match any GP-STORE order label.");
        }
        Order order = found.get();

        if (order.getQrTokenUsedAt() != null) {
            // The token was consumed by an earlier scan. Naming who has it is
            // the entire point - a worker holding a carton needs to know who to
            // hand it to, not merely that they cannot have it.
            String holder = order.getPackedByPartner() == null
                    ? "another worker"
                    : order.getPackedByPartner().getName();
            return reject(order, worker, clientRequestId, "ALREADY_SCANNED",
                    "Order already assigned to " + holder + ".");
        }

        if (order.getOrderStatus() == null || !SCANNABLE.contains(order.getOrderStatus())) {
            String state = order.getOrderStatus() == null ? "unknown" : order.getOrderStatus().name();
            return reject(order, worker, clientRequestId, "NOT_ELIGIBLE",
                    "This order cannot be packed right now (it is " + state + ").");
        }

        // ---- Authorisation, decided here and nowhere else ---------------
        //
        // THE TERRITORY IS THE ONE THIS RIDER'S SHOP DREW (W4). A rider works
        // for exactly one shop, and the order is that shop's; reading the
        // stamp off the address would ask a different shop's map who is
        // allowed to pack this - and under a marketplace would load that
        // shop's row and fail the scan outright.
        DeliverySubzone subzone = territoryResolver
                .territoryForDelivery(order.getAddress()).orElse(null);
        Authorisation auth = authorise(worker, order, subzone);
        if (!auth.allowed()) {
            return reject(order, worker, clientRequestId, "NOT_AUTHORISED", auth.reason());
        }

        // ---- Commit --------------------------------------------------
        LocalDateTime now = LocalDateTime.now();
        order.setQrTokenUsedAt(now);
        order.setPackedByPartner(worker);
        order.setPackedAt(now);
        order.setOrderStatus(OrderStatus.PACKED);
        orderRepository.save(order);

        // THE DELIVERY MOVES WITH THE ORDER, when there is one.
        //
        // Packing and "this delivery is packed" are the same event seen from
        // two sides, so making the worker press a second button for it would
        // be a second chance to forget. There often is no delivery row yet -
        // these are shop employees who pack whatever is on the bench, and a
        // delivery is created at assignment, which may come later - so its
        // absence is normal and not an error.
        //
        // Guarded by the same transition table as every other status change:
        // a delivery already OUT_FOR_DELIVERY does not go backwards because
        // somebody re-scanned a label.
        deliveryRepository.findByOrderId(order.getId()).ifPresent(delivery -> {
            DeliveryStatus current = DeliveryStatus.parse(delivery.getDeliveryStatus()).orElse(null);
            if (DeliveryStatusTransitions.isAllowed(current, DeliveryStatus.PACKED)
                    && current != DeliveryStatus.PACKED) {
                delivery.setDeliveryStatus(DeliveryStatus.PACKED.name());
                deliveryRepository.save(delivery);
            }
        });

        OrderScanEvent event = newEvent(order, worker, clientRequestId, subzone);
        event.setOutcome("ACCEPTED");
        event.setReason(auth.reason());
        event.setScannedAt(now);

        try {
            scanRepository.save(event);
        } catch (DataIntegrityViolationException raced) {
            // Two taps arriving close enough together to pass the replay check
            // above concurrently. The unique index on (partner, request id) is
            // the real guard; this turns the collision into the answer the
            // first request produced.
            Optional<OrderScanEvent> winner = clientRequestId == null ? Optional.empty()
                    : scanRepository.findByPartnerIdAndClientRequestId(worker.getId(), clientRequestId);
            if (winner.isPresent()) {
                return replay(winner.get(), worker);
            }
            throw raced;
        }

        // Best effort, after commit, off the request thread. The scan is
        // already recorded; a Firebase hiccup must not hold this connection.
        if (order.getCustomer() != null) {
            order.getCustomer().getFcmToken();
        }
        order.getOrderNumber();
        final Order notifyOrder = order;
        afterCommitExecutor.runAfterCommit("Pack scan notification", order.getId(),
                () -> notificationService.notifyOrderStatusChange(notifyOrder, OrderStatus.PACKED));

        auditLogService.log("ORDER_PACK_SCAN", "Order", order.getId(),
                worker.getName() + " scanned this order as packed. " + auth.reason());

        return new ScanResult(true, "ACCEPTED", "Order " + order.getOrderNumber() + " is yours.",
                order.getOrderNumber(), worker.getName(), workerCode(worker),
                zoneCodeOf(subzone), subzoneCodeOf(subzone), now, false,
                viewOf(order));
    }

    /**
     * Builds the worker's view of an order it has just been given.
     *
     * Reads the payment because "how much cash to take" is not answerable from
     * the order alone - a prepaid order and a COD order have the same total
     * and opposite answers. One extra query on the scan path, which is the
     * round trip it saves the app from making itself.
     */
    WorkerOrderView viewOf(Order order) {
        com.gpstore.entity.Delivery delivery = deliveryRepository.findByOrderId(order.getId()).orElse(null);
        com.gpstore.entity.Payment payment = paymentRepository.findByOrderId(order.getId()).orElse(null);

        List<String> allowedNext = delivery == null
                ? List.of()
                : DeliveryStatusTransitions
                        .nextFrom(DeliveryStatus.parse(delivery.getDeliveryStatus()).orElse(null))
                        .stream().map(Enum::name).toList();

        return WorkerOrderView.of(order, delivery, payment, allowedNext);
    }

    // --------------------------------------------------------- authorisation

    private record Authorisation(boolean allowed, String reason) {
    }

    /**
     * May this worker take this order?
     *
     * The ladder, in order, and each rung exists for a situation that actually
     * happens in a shop:
     *
     *   1. An administrator named this worker on this order. Outranks
     *      everything, because it is a human overriding the map on purpose.
     *   2. Dispatch already assigned the order to them.
     *   3. They are the primary worker for the order's territory. The normal
     *      case, and the one the permanent-territory design is built around.
     *   4. They are a named standing backup for that territory - somebody who
     *      has actually ridden it, named in advance.
     *   5. The order has no territory at all. Refusing here would strand a real
     *      carton at a real counter over a hole in the map, so it is allowed
     *      and recorded as exactly that.
     *
     * Anything else is refused, and the message names the worker who should be
     * taking it so the carton can be handed over rather than argued about.
     */
    private Authorisation authorise(DeliveryPartner worker, Order order, DeliverySubzone subzone) {
        if (order.getAssignedWorkerPartner() != null
                && order.getAssignedWorkerPartner().getId().equals(worker.getId())) {
            return new Authorisation(true, "Assigned to this worker by an administrator.");
        }

        if (order.getAssignedWorkerPartner() != null) {
            return new Authorisation(false,
                    "An administrator assigned this order to "
                            + order.getAssignedWorkerPartner().getName() + ".");
        }

        if (subzone == null) {
            return new Authorisation(true,
                    "This order's address is in no drawn territory, so no worker owns it.");
        }

        DeliveryPartner primary = subzone.getPrimaryPartner();
        if (primary != null && primary.getId().equals(worker.getId())) {
            return new Authorisation(true, "Primary worker for " + subzone.getCode() + ".");
        }

        for (SubzoneBackupPartner backup
                : backupRepository.findBySubzoneIdOrderByPriorityAscIdAsc(subzone.getId())) {
            if (backup.getPartner() != null && backup.getPartner().getId().equals(worker.getId())) {
                return new Authorisation(true,
                        "Standing backup for " + subzone.getCode() + " (priority "
                                + backup.getPriority() + ").");
            }
        }

        String owner = primary == null ? "nobody yet" : primary.getName();
        return new Authorisation(false,
                "This order is in " + subzone.getCode() + ", which belongs to " + owner
                        + ". Ask an administrator to reassign it if you need to take it.");
    }

    // -------------------------------------------------------------- plumbing

    private ScanResult reject(Order order, DeliveryPartner worker, String clientRequestId,
                              String outcome, String message) {
        DeliverySubzone subzone = order == null
                ? null
                : territoryResolver.territoryForDelivery(order.getAddress()).orElse(null);

        OrderScanEvent event = newEvent(order, worker, clientRequestId, subzone);
        event.setOutcome(outcome);
        // The recorded reason is the sentence the worker was shown, verbatim,
        // so the audit and the worker's memory can never disagree.
        event.setReason(message);
        try {
            scanRepository.save(event);
        } catch (DataIntegrityViolationException duplicate) {
            // A retried rejection. Nothing to record twice.
            log.debug("Duplicate rejected scan for request {}", clientRequestId);
        }

        return new ScanResult(false, outcome, message,
                order == null ? null : order.getOrderNumber(),
                worker.getName(), workerCode(worker),
                zoneCodeOf(subzone), subzoneCodeOf(subzone), event.getScannedAt(), false,
                // Deliberately null. A refusal must not carry the contents of
                // the order it just refused to hand over.
                null);
    }

    private ScanResult replay(OrderScanEvent previous, DeliveryPartner worker) {
        return new ScanResult("ACCEPTED".equals(previous.getOutcome()), previous.getOutcome(),
                previous.getReason(), previous.getOrderNumber(), worker.getName(),
                workerCode(worker), previous.getZoneCode(), previous.getSubzoneCode(),
                previous.getScannedAt(), true, null);
    }

    private OrderScanEvent newEvent(Order order, DeliveryPartner worker,
                                    String clientRequestId, DeliverySubzone subzone) {
        OrderScanEvent event = new OrderScanEvent();
        event.setOrderId(order == null ? null : order.getId());
        event.setOrderNumber(order == null ? null : order.getOrderNumber());
        event.setPartnerId(worker.getId());
        event.setWorkerName(worker.getName());
        event.setAction("PACKED");
        event.setZoneCode(zoneCodeOf(subzone));
        event.setSubzoneCode(subzoneCodeOf(subzone));
        event.setScannedAt(LocalDateTime.now());
        event.setClientRequestId(clientRequestId == null || clientRequestId.isBlank()
                ? null : clientRequestId);
        event.setPerformedByAdmin(false);
        return event;
    }

    static String zoneCodeOf(DeliverySubzone subzone) {
        return subzone == null || subzone.getZone() == null ? null : subzone.getZone().getCode();
    }

    static String subzoneCodeOf(DeliverySubzone subzone) {
        return subzone == null ? null : subzone.getCode();
    }

    /**
     * The short code a worker is known by on the shop floor - D21 and so on.
     *
     * Derived from the roster id rather than stored, because a second
     * hand-maintained identifier is a second thing to get out of step with the
     * first. If the shop later wants codes that are not sequential, that is a
     * column; until then this is honest and cannot drift.
     */
    static String workerCode(DeliveryPartner worker) {
        return worker == null || worker.getId() == null ? null : "D" + worker.getId();
    }

    /** The territory this worker is primary for, if any. */
    @Transactional(readOnly = true)
    public Optional<DeliverySubzone> territoryOf(DeliveryPartner worker) {
        return subzoneRepository.findFirstByPrimaryPartner_Id(worker.getId());
    }
}
