package com.gpstore.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gpstore.entity.Order;
import com.gpstore.entity.Payment;
import com.gpstore.entity.PaymentProviderEvent;
import com.gpstore.entity.PaymentProviderEvent.Outcome;
import com.gpstore.enums.OrderStatus;
import com.gpstore.enums.PaymentProvider;
import com.gpstore.enums.PaymentStatus;
import com.gpstore.exception.BadRequestException;
import com.gpstore.exception.ConflictException;
import com.gpstore.exception.ResourceNotFoundException;
import com.gpstore.dto.response.GatewayCheckoutResponse;
import com.gpstore.payment.gateway.CashfreeProperties;
import com.gpstore.payment.gateway.CashfreeSignatureVerifier;
import com.gpstore.payment.gateway.PaymentGateway;
import com.gpstore.payment.gateway.PaymentGateway.GatewayOrderStatus;
import com.gpstore.repository.OrderRepository;
import com.gpstore.repository.PaymentProviderEventRepository;
import com.gpstore.repository.PaymentRepository;
import com.gpstore.service.AuditLogService;
import com.gpstore.service.OrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Optional;

/**
 * Everything gateway-specific about a payment, kept out of PaymentService.
 *
 * PaymentService already owns COD and direct UPI and is six hundred lines;
 * folding a gateway into it would make the file the place where every
 * payment concern lives. This class talks to the gateway and applies its
 * verdicts; PaymentService keeps doing what it does.
 *
 * THE ONE RULE THIS CLASS EXISTS TO ENFORCE: a payment becomes SUCCESS only
 * when the PROVIDER says so, on a path the client cannot forge. There are
 * exactly two such paths - a signature-verified webhook, and a server-to-
 * server status fetch - and both funnel into the same applyVerdict method
 * so they cannot drift apart.
 */
@Service
public class GatewayPaymentService {

    private static final Logger log = LoggerFactory.getLogger(GatewayPaymentService.class);

    private static final String CURRENCY = "INR";

    /**
     * Prefix on every gateway order id we mint, followed by our internal
     * order id and a random attempt suffix:  GP-1234-7f3a9b
     *
     * THE INTERNAL ID IS EMBEDDED ON PURPOSE. A customer who fails and
     * retries gets a NEW gateway order - Cashfree will not accept a second
     * payment against a terminated one - so the id stored on the payment row
     * is only ever the LATEST attempt. A webhook for a superseded attempt
     * still has to find its way home, and it does: parse the internal id out
     * of the prefix. Without this a late webhook for attempt 1 would look
     * like an unknown order.
     */
    private static final String ORDER_ID_PREFIX = "GP-";

    private final PaymentGateway gateway;
    private final CashfreeProperties properties;
    private final CashfreeSignatureVerifier signatureVerifier;
    private final PaymentRepository paymentRepository;
    private final OrderRepository orderRepository;
    private final PaymentProviderEventRepository eventRepository;
    private final OrderService orderService;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;
    private final SecureRandom random = new SecureRandom();

    /**
     * Self, through the proxy. Calling prepareCheckout directly from
     * startCheckout would bypass Spring's transaction interceptor entirely -
     * the call never leaves the object, so no proxy is involved and
     * @Transactional silently does nothing. Same pattern PaymentService
     * already uses.
     */
    private final GatewayPaymentService self;

    public GatewayPaymentService(PaymentGateway gateway,
                                 CashfreeProperties properties,
                                 CashfreeSignatureVerifier signatureVerifier,
                                 PaymentRepository paymentRepository,
                                 OrderRepository orderRepository,
                                 PaymentProviderEventRepository eventRepository,
                                 OrderService orderService,
                                 AuditLogService auditLogService,
                                 ObjectMapper objectMapper,
                                 @org.springframework.context.annotation.Lazy GatewayPaymentService self) {
        this.gateway = gateway;
        this.properties = properties;
        this.signatureVerifier = signatureVerifier;
        this.paymentRepository = paymentRepository;
        this.orderRepository = orderRepository;
        this.eventRepository = eventRepository;
        this.orderService = orderService;
        this.auditLogService = auditLogService;
        this.objectMapper = objectMapper;
        this.self = self;
    }

    // ------------------------------------------------------------------
    // 1. Starting a checkout
    // ------------------------------------------------------------------

    /**
     * Registers this order with the gateway and returns the session the app
     * opens.
     *
     * THE AMOUNT COMES FROM THE ORDER ROW, never from a request. There is no
     * parameter here that could carry one. Whatever the client believes the
     * total is, what reaches Cashfree is what placeOrder computed from real
     * variant prices, the delivery-fee formula and any validated coupon -
     * so a tampered cart cannot produce a tampered charge.
     */
    /**
     * NOT @Transactional, deliberately, and this is the whole reason
     * startCheckout is split in two.
     *
     * Creating the gateway order is an outbound HTTPS call with a ten-second
     * timeout. Making it inside a transaction would hold the order row lock,
     * the payment row lock AND a Hikari connection for the whole of it - on
     * a ten-connection pool and half a vCPU, a handful of simultaneous
     * checkouts against a slow Cashfree would exhaust the pool and stall
     * every other request in the application, including ones with nothing to
     * do with payment.
     *
     * So: a short transaction validates the order and claims a provider
     * order id, the lock is released, and only then does the network call
     * happen. "Claims", not "reserves" - no stock is touched anywhere in
     * this class. Inventory was already deducted at placeOrder, under its
     * own lock, long before any of this runs.
     */
    public GatewayCheckoutResponse startCheckout(Long orderId, Long callerCustomerId) {
        CheckoutIntent intent = self.prepareCheckout(orderId, callerCustomerId);

        PaymentGateway.GatewaySession session = gateway.createSession(
                new PaymentGateway.GatewaySessionRequest(
                        intent.providerOrderId(),
                        intent.amount(),
                        CURRENCY,
                        // Stable and not a secret. Nothing beyond what the
                        // gateway needs to reach the customer is sent.
                        "cust_" + callerCustomerId,
                        intent.customerPhone(),
                        intent.customerName(),
                        intent.customerEmail(),
                        properties.getReturnUrl(),
                        properties.getNotifyUrl()));

        auditLogService.log("GATEWAY_CHECKOUT_STARTED", "Payment", intent.paymentId(),
                "orderId=" + orderId + ", provider=CASHFREE, providerOrderId=" + intent.providerOrderId());

        return new GatewayCheckoutResponse(
                orderId,
                intent.paymentId(),
                PaymentProvider.CASHFREE.name(),
                intent.providerOrderId(),
                session.paymentSessionId(),
                intent.amount(),
                CURRENCY,
                properties.isProduction() ? "production" : "sandbox");
    }

    /**
     * Everything the gateway call needs, read under the lock and carried out
     * of the transaction as plain values.
     *
     * Values rather than entities on purpose: touching a lazy association
     * after the transaction closed is a LazyInitializationException, and the
     * customer's name and phone are exactly the kind of field that would be.
     */
    public record CheckoutIntent(Long paymentId, String providerOrderId, BigDecimal amount,
                                 String customerPhone, String customerName, String customerEmail) {}

    @Transactional
    public CheckoutIntent prepareCheckout(Long orderId, Long callerCustomerId) {
        // ORDER then PAYMENT, matching every other mutating payment path in
        // this codebase. Diverging here is how a deadlock gets introduced.
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));

        // Not-found rather than forbidden: a customer probing order ids
        // should not be able to tell an order they do not own from one that
        // does not exist.
        if (order.getCustomer() == null || !order.getCustomer().getId().equals(callerCustomerId)) {
            throw new ResourceNotFoundException("Order not found");
        }

        Payment payment = paymentRepository.findByOrderIdForUpdate(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found for this order"));

        // ALREADY PAID: the double-tap and the retry-after-success case.
        // Returning the existing state rather than minting a second session
        // is what stops one order being paid twice.
        if (payment.getPaymentStatus() == PaymentStatus.SUCCESS) {
            throw new ConflictException("This order has already been paid.");
        }
        if (payment.getPaymentStatus() == PaymentStatus.REFUNDED
                || payment.getPaymentStatus() == PaymentStatus.REFUND_PENDING) {
            throw new ConflictException("This order has been refunded.");
        }
        if (order.getOrderStatus() == OrderStatus.CANCELLED) {
            throw new ConflictException("This order has been cancelled.");
        }

        BigDecimal amount = order.getTotalAmount();
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BadRequestException("This order has no payable amount.");
        }

        // Minted and PERSISTED before the gateway is called, under the lock.
        // Two simultaneous taps of Pay both reach this line, but only one
        // holds the row lock at a time and the second sees the first's
        // committed state - so the id the app is handed is always the one on
        // the payment row, never a second live session competing with it.
        String providerOrderId = mintProviderOrderId(orderId);

        payment.setProvider(PaymentProvider.CASHFREE);
        payment.setProviderOrderId(providerOrderId);
        payment.setCurrency(CURRENCY);
        payment.setPaymentStatus(PaymentStatus.PENDING);
        payment.setFailureReason(null);
        payment.setUpdatedAt(LocalDateTime.now());
        paymentRepository.save(payment);

        // Read here, inside the transaction, because these are lazy.
        return new CheckoutIntent(
                payment.getId(),
                providerOrderId,
                amount,
                order.getCustomer().getMobileNumber(),
                order.getCustomer().getFullName(),
                order.getCustomer().getEmail());
    }

    // ------------------------------------------------------------------
    // 2. The webhook
    // ------------------------------------------------------------------

    /**
     * Applies a webhook, or explains why it did not.
     *
     * Returns true when the caller should answer 2xx. That includes events
     * that changed nothing - a duplicate, an unknown order, an event we do
     * not act on. Answering non-2xx to those would make Cashfree retry
     * something that will never succeed, forever.
     *
     * A BAD SIGNATURE IS THE ONE CASE THAT MUST NOT BE 2xx, and it is
     * rejected before this method is reached.
     */
    @Transactional
    public WebhookResult applyWebhook(String rawBody, String signature, String timestamp) {
        CashfreeSignatureVerifier.Result verification =
                signatureVerifier.verify(rawBody, signature, timestamp);

        if (verification != CashfreeSignatureVerifier.Result.VALID) {
            // Deliberately no body, no signature, no secret in this line.
            log.warn("Rejected Cashfree webhook: {}", verification);
            return WebhookResult.rejected(verification.name());
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(rawBody);
        } catch (Exception e) {
            return WebhookResult.rejected("MALFORMED_JSON");
        }

        JsonNode data = root.path("data");
        JsonNode orderNode = data.path("order");
        JsonNode paymentNode = data.path("payment");

        String eventType = text(root, "type");
        String providerOrderId = text(orderNode, "order_id");
        String eventId = resolveEventId(root, paymentNode, providerOrderId, eventType);

        if (providerOrderId == null || providerOrderId.isBlank()) {
            recordEvent(eventId, eventType, null, null, Outcome.UNKNOWN_ORDER, "no order_id in payload");
            return WebhookResult.accepted(Outcome.UNKNOWN_ORDER);
        }

        // THE DEDUP is the unique insert in recordEvent below, and it is
        // what makes a retried delivery safe. It runs AFTER applyVerdict
        // rather than before - it needs the outcome to record - and that is
        // fine precisely because both are inside THIS transaction: a
        // colliding insert throws, the transaction rolls back, and the state
        // change is undone as if it never happened.
        //
        // A check-then-act would let two simultaneous deliveries both pass;
        // a unique constraint cannot. Note applyVerdict independently
        // refuses to touch an already-SUCCESS payment, so a duplicate is
        // stopped twice over.
        Payment payment = locatePayment(providerOrderId).orElse(null);
        if (payment == null) {
            recordEvent(eventId, eventType, null, providerOrderId, Outcome.UNKNOWN_ORDER,
                    "no payment matches this provider order id");
            return WebhookResult.accepted(Outcome.UNKNOWN_ORDER);
        }

        GatewayOrderStatus verdict = new GatewayOrderStatus(
                providerOrderId,
                text(paymentNode, "cf_payment_id"),
                mapPaymentStatus(text(paymentNode, "payment_status")),
                decimal(paymentNode, "payment_amount"),
                text(paymentNode, "payment_currency"),
                text(paymentNode, "payment_message"));

        Outcome outcome = applyVerdict(payment, verdict, "webhook");
        recordEvent(eventId, eventType, payment, providerOrderId, outcome, null);
        return WebhookResult.accepted(outcome);
    }

    // ------------------------------------------------------------------
    // 3. Recovery: ask the provider directly
    // ------------------------------------------------------------------

    /**
     * Re-checks one order against the gateway and applies whatever it says.
     *
     * THIS IS WHY A LOST WEBHOOK IS NOT A LOST PAYMENT. The app calls it
     * when it returns from checkout - however it returns, including from a
     * cancelled screen or after being killed - and any later view of the
     * order can call it too. The answer comes from Cashfree's servers over a
     * credentialed connection, so it carries exactly the same authority as
     * the webhook and none of the client's.
     *
     * It is also the reason the app never needs to poll: one call at the
     * moment the customer is actually looking, rather than a loop.
     */
    @Transactional
    public PaymentStatus reconcile(Long orderId, Long callerCustomerId) {
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));

        if (order.getCustomer() == null || !order.getCustomer().getId().equals(callerCustomerId)) {
            throw new ResourceNotFoundException("Order not found");
        }

        Payment payment = paymentRepository.findByOrderIdForUpdate(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found for this order"));

        // Terminal already, or never a gateway payment: nothing to ask about.
        if (payment.getProviderOrderId() == null
                || payment.getPaymentStatus() == PaymentStatus.SUCCESS
                || payment.getPaymentStatus() == PaymentStatus.REFUNDED) {
            return payment.getPaymentStatus();
        }

        GatewayOrderStatus verdict = gateway.fetchOrderStatus(payment.getProviderOrderId());
        applyVerdict(payment, verdict, "reconcile");
        return payment.getPaymentStatus();
    }

    // ------------------------------------------------------------------
    // The single place a gateway verdict becomes our state
    // ------------------------------------------------------------------

    /**
     * Both the webhook and the status fetch end here, and that is the point:
     * two sources of truth applying two slightly different rules is how
     * "webhook arrived first" and "client returned first" end up disagreeing
     * about the same payment.
     */
    private Outcome applyVerdict(Payment payment, GatewayOrderStatus verdict, String source) {
        // ALREADY SETTLED. The duplicate-success case the brief calls out:
        // a second success event must do nothing at all. Not re-save, not
        // re-advance the order, not re-notify.
        if (payment.getPaymentStatus() == PaymentStatus.SUCCESS) {
            return Outcome.ALREADY_SETTLED;
        }

        if (verdict.state() == GatewayOrderStatus.State.PAID) {
            // AMOUNT AND CURRENCY ARE CHECKED BEFORE ANYTHING IS BANKED.
            // A payment that settled for less than the order, or in another
            // currency, is not this order being paid - and marking it paid
            // would hand over groceries for the difference.
            if (!amountMatches(payment.getAmount(), verdict.amount())) {
                markMismatch(payment, "amount mismatch: expected " + payment.getAmount()
                        + " got " + verdict.amount());
                return Outcome.MISMATCH;
            }
            if (verdict.currency() != null && !CURRENCY.equalsIgnoreCase(verdict.currency())) {
                markMismatch(payment, "currency mismatch: expected " + CURRENCY + " got " + verdict.currency());
                return Outcome.MISMATCH;
            }

            payment.setPaymentStatus(PaymentStatus.SUCCESS);
            payment.setProviderPaymentId(verdict.providerPaymentId());
            payment.setPaymentDate(LocalDateTime.now());
            payment.setFailureReason(null);
            payment.setUpdatedAt(LocalDateTime.now());
            persist(payment);

            // Only NOW does the order become confirmed. This is the line the
            // whole design exists to protect.
            advanceOrderIfStillPending(payment.getOrder());

            auditLogService.log("GATEWAY_PAYMENT_SUCCESS", "Payment", payment.getId(),
                    "source=" + source + ", providerOrderId=" + verdict.providerOrderId());
            return Outcome.APPLIED;
        }

        if (verdict.state() == GatewayOrderStatus.State.FAILED
                || verdict.state() == GatewayOrderStatus.State.CANCELLED
                || verdict.state() == GatewayOrderStatus.State.EXPIRED) {

            payment.setPaymentStatus(switch (verdict.state()) {
                case EXPIRED -> PaymentStatus.EXPIRED;
                case CANCELLED -> PaymentStatus.CANCELLED;
                default -> PaymentStatus.FAILED;
            });
            payment.setFailureReason(truncate(verdict.failureReason()));
            payment.setUpdatedAt(LocalDateTime.now());
            persist(payment);

            // The ORDER is deliberately NOT cancelled here. A failed attempt
            // is not an abandoned order - the customer is usually still on
            // the screen about to try another card, and cancelling would
            // restore stock underneath them. The existing expiry sweep is
            // what eventually releases a genuinely abandoned order.
            auditLogService.log("GATEWAY_PAYMENT_" + payment.getPaymentStatus(), "Payment", payment.getId(),
                    "source=" + source + ", providerOrderId=" + verdict.providerOrderId());
            return Outcome.APPLIED;
        }

        // ACTIVE or UNKNOWN: the customer has not finished. Deliberately not
        // treated as failure - a session still in progress is exactly what
        // "still typing an OTP" looks like.
        return Outcome.IGNORED;
    }

    private void markMismatch(Payment payment, String detail) {
        payment.setPaymentStatus(PaymentStatus.FAILED);
        payment.setFailureReason(truncate(detail));
        payment.setUpdatedAt(LocalDateTime.now());
        persist(payment);
        // Loud, because this is either a bug or an attack and both need a
        // human. The detail carries amounts, never an instrument.
        log.error("Gateway payment rejected for payment {}: {}", payment.getId(), detail);
        auditLogService.log("GATEWAY_PAYMENT_MISMATCH", "Payment", payment.getId(), detail);
    }

    private void advanceOrderIfStillPending(Order order) {
        if (order != null && order.getOrderStatus() == OrderStatus.PENDING_CONFIRMATION) {
            orderService.updateOrderStatus(order.getId(), OrderStatus.CONFIRMED);
        }
    }

    /**
     * saveAndFlush, not save.
     *
     * The payment is a managed entity here, so save() issues no SQL and
     * defers the write to commit - past the unique constraints on
     * provider_payment_id. Flushing puts a duplicate-payment collision
     * inside this method where it can be seen, rather than at commit where
     * it escapes as an untranslated error.
     */
    private void persist(Payment payment) {
        try {
            paymentRepository.saveAndFlush(payment);
        } catch (DataIntegrityViolationException e) {
            // Another transaction banked this same provider payment id first.
            // That is the race working exactly as intended.
            throw new ConflictException("This payment has already been recorded.");
        }
    }

    /**
     * Finds the payment a webhook belongs to, locking ORDER THEN PAYMENT.
     *
     * THE LOCK ORDER IS THE WHOLE POINT OF THIS METHOD'S SHAPE, and it is
     * why the payment is not simply looked up directly. prepareCheckout,
     * reconcile and PaymentService.lockOrderThenPayment all take the order
     * row first and the payment row second. A webhook that took the payment
     * first and then reached the order - which is exactly what
     * advanceOrderIfStillPending does a few frames later - would give two
     * transactions the same two locks in opposite orders. Postgres would
     * detect the cycle and kill one of them.
     *
     * It would have self-healed, because a killed webhook is a 500 and
     * Cashfree redelivers, and the dedup makes redelivery safe. But the
     * customer-visible half would not: /verify shares applyVerdict and would
     * have surfaced the deadlock as a failed request while someone watched
     * their payment screen.
     *
     * Which order to lock is derivable without reading anything, because we
     * minted the id: GP-<orderId>-<random>. The unlocked read below is only
     * the fallback for an id that is not ours in that shape, and it is used
     * solely to learn WHICH order to lock - never to decide anything. The
     * authoritative read happens after the lock is held.
     *
     * Exact match first, then the internal id. That fallback is what lets a
     * LATE webhook for a superseded retry attempt still find its order - the
     * payment row only ever carries the newest attempt's id, so attempt 1's
     * webhook would otherwise look like an unknown order.
     */
    private Optional<Payment> locatePayment(String providerOrderId) {
        Long internalOrderId = internalOrderIdFrom(providerOrderId);

        if (internalOrderId == null) {
            internalOrderId = paymentRepository.findByProviderOrderId(providerOrderId)
                    .map(Payment::getOrder)
                    .map(Order::getId)
                    .orElse(null);
            if (internalOrderId == null) {
                return Optional.empty();
            }
        }

        // Lock A. Not dereferenced - taking it is the entire purpose. If the
        // order has vanished there is nothing to apply a verdict to.
        if (orderRepository.findByIdForUpdate(internalOrderId).isEmpty()) {
            return Optional.empty();
        }

        // Lock B, always after A.
        Optional<Payment> exact = paymentRepository.findByProviderOrderIdForUpdate(providerOrderId);
        return exact.isPresent()
                ? exact
                : paymentRepository.findByOrderIdForUpdate(internalOrderId);
    }

    static Long internalOrderIdFrom(String providerOrderId) {
        if (providerOrderId == null || !providerOrderId.startsWith(ORDER_ID_PREFIX)) {
            return null;
        }
        String rest = providerOrderId.substring(ORDER_ID_PREFIX.length());
        int dash = rest.indexOf('-');
        String idPart = dash > 0 ? rest.substring(0, dash) : rest;
        try {
            return Long.parseLong(idPart);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String mintProviderOrderId(Long orderId) {
        byte[] suffix = new byte[6];
        random.nextBytes(suffix);
        return ORDER_ID_PREFIX + orderId + "-"
                + Base64.getUrlEncoder().withoutPadding().encodeToString(suffix);
    }

    /**
     * Recorded in the SAME transaction that applied the change, so the
     * dedup constraint and the state change commit or roll back together.
     */
    private void recordEvent(String eventId, String eventType, Payment payment,
                             String providerOrderId, Outcome outcome, String detail) {
        PaymentProviderEvent event = PaymentProviderEvent.of(
                PaymentProvider.CASHFREE, eventId, eventType, providerOrderId, outcome, truncate(detail));
        event.setPayment(payment);
        try {
            eventRepository.saveAndFlush(event);
        } catch (DataIntegrityViolationException e) {
            // Already recorded: this delivery is a retry of one we handled.
            // Rethrown so the whole transaction rolls back and the state
            // change above is undone - which is the entire point.
            throw new DuplicateEventException(eventId);
        }
    }

    /**
     * A stable id for this delivery.
     *
     * Cashfree does not currently send a dedicated event id header, so this
     * is derived from what identifies the event: the payment it concerns and
     * its type. Two deliveries of the SAME event produce the same key and
     * collide on the unique constraint; a genuinely different event (a
     * failure then a success on the same order) produces a different one and
     * is allowed through.
     */
    private String resolveEventId(JsonNode root, JsonNode paymentNode, String providerOrderId, String eventType) {
        String cfPaymentId = text(paymentNode, "cf_payment_id");
        if (cfPaymentId != null && !cfPaymentId.isBlank()) {
            return cfPaymentId + ":" + (eventType == null ? "event" : eventType);
        }
        String status = text(paymentNode, "payment_status");
        return (providerOrderId == null ? "unknown" : providerOrderId)
                + ":" + (eventType == null ? "event" : eventType)
                + ":" + (status == null ? "" : status);
    }

    /**
     * compareTo, not equals. BigDecimal.equals is scale-sensitive, so
     * 100 and 100.00 are unequal - which would reject every correct payment
     * Cashfree ever settles, since it returns two decimal places and our
     * totals may not.
     */
    static boolean amountMatches(BigDecimal expected, BigDecimal actual) {
        if (expected == null || actual == null) return false;
        return expected.compareTo(actual) == 0;
    }

    private static String truncate(String value) {
        if (value == null) return null;
        return value.length() <= 500 ? value : value.substring(0, 500);
    }

    private static String text(JsonNode node, String field) {
        return node != null && node.hasNonNull(field) ? node.get(field).asText() : null;
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        return node != null && node.hasNonNull(field) ? node.get(field).decimalValue() : null;
    }

    static GatewayOrderStatus.State mapPaymentStatus(String paymentStatus) {
        if (paymentStatus == null) return GatewayOrderStatus.State.UNKNOWN;
        return switch (paymentStatus.toUpperCase()) {
            case "SUCCESS" -> GatewayOrderStatus.State.PAID;
            case "FAILED", "USER_DROPPED" -> GatewayOrderStatus.State.FAILED;
            case "CANCELLED", "VOID" -> GatewayOrderStatus.State.CANCELLED;
            case "PENDING", "NOT_ATTEMPTED" -> GatewayOrderStatus.State.ACTIVE;
            default -> GatewayOrderStatus.State.UNKNOWN;
        };
    }

    /** Thrown when a webhook is a retry of one already applied. */
    public static class DuplicateEventException extends RuntimeException {
        public DuplicateEventException(String eventId) {
            super("Duplicate gateway event: " + eventId);
        }
    }

    public record WebhookResult(boolean accepted, String reason, Outcome outcome) {
        static WebhookResult accepted(Outcome outcome) { return new WebhookResult(true, null, outcome); }
        static WebhookResult rejected(String reason) { return new WebhookResult(false, reason, null); }
    }
}
