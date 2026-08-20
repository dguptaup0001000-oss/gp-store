package com.gpstore.service;

import com.gpstore.dto.request.InitiatePaymentRequest;
import com.gpstore.dto.response.PaymentInitiationResponse;
import com.gpstore.enums.OrderStatus;
import com.gpstore.enums.PaymentMethod;
import com.gpstore.enums.PaymentStatus;
import com.gpstore.entity.Order;
import com.gpstore.entity.Payment;
import com.gpstore.exception.BadRequestException;
import com.gpstore.exception.ConflictException;
import com.gpstore.exception.ResourceNotFoundException;
import com.gpstore.repository.OrderRepository;
import com.gpstore.repository.PaymentRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class PaymentService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PaymentService.class);

    private final PaymentRepository paymentRepository;
    private final OrderRepository orderRepository;
    private final AuditLogService auditLogService;
    private final UpiPaymentService upiPaymentService;
    private final OrderService orderService;
    private final int upiTimeoutMinutes;
    private final int expiryBatchSize;
    private final int maxExpiryBatchesPerRun;

    /**
     * This bean's own proxy, needed so the expiry sweep's per-payment
     * REQUIRES_NEW transaction actually takes effect. A plain
     * this.expireOneStalePayment(...) call would bypass Spring's
     * transactional proxy entirely (self-invocation), silently running every
     * batch in the caller's transaction - or in none at all - which is
     * exactly the "one giant transaction" behaviour the batching exists to
     * avoid. @Lazy breaks the circular dependency this would otherwise
     * create on itself during construction.
     */
    private final PaymentService self;

    public PaymentService(
            PaymentRepository paymentRepository,
            OrderRepository orderRepository,
            AuditLogService auditLogService,
            UpiPaymentService upiPaymentService,
            OrderService orderService,
            @org.springframework.context.annotation.Lazy PaymentService self,
            @org.springframework.beans.factory.annotation.Value("${payment.upi-timeout-minutes}") int upiTimeoutMinutes,
            @org.springframework.beans.factory.annotation.Value("${payment.expiry-batch-size:100}") int expiryBatchSize,
            @org.springframework.beans.factory.annotation.Value("${payment.expiry-max-batches-per-run:50}") int maxExpiryBatchesPerRun) {

        this.paymentRepository = paymentRepository;
        this.orderRepository = orderRepository;
        this.auditLogService = auditLogService;
        this.upiPaymentService = upiPaymentService;
        this.orderService = orderService;
        this.self = self;
        this.upiTimeoutMinutes = upiTimeoutMinutes;
        this.expiryBatchSize = expiryBatchSize;
        this.maxExpiryBatchesPerRun = maxExpiryBatchesPerRun;
    }

    /**
     * Auto-expires UPI payments nobody ever confirmed within the timeout
     * window - otherwise an abandoned checkout leaves a payment sitting
     * PENDING forever with no resolution. Runs every 5 minutes; doesn't touch
     * the order itself (a customer can still retry payment separately).
     *
     * @SchedulerLock ensures only one app instance actually runs this on any
     * given tick, once more than one instance exists - see
     * config.SchedulerLockConfig. lockAtMostFor is a safety ceiling (releases
     * the lock even if this instance crashed mid-run); lockAtLeastFor stops a
     * second instance from re-running it moments later on a fast, mostly-empty
     * pass.
     */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelayString = "${payment.expiry-interval-ms:300000}", initialDelayString = "${payment.expiry-initial-delay-ms:30000}")
    @net.javacrumbs.shedlock.spring.annotation.SchedulerLock(
            name = "expireStalePendingUpiPayments",
            lockAtMostFor = "10m",
            lockAtLeastFor = "1m")
    public void expireStalePendingUpiPayments() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(upiTimeoutMinutes);

        // Deliberately NOT @Transactional at this level, and deliberately
        // not one query returning every stale payment. This sweep covers
        // "everything abandoned since the last successful run", so its size
        // grows with traffic and with any gap in the scheduler running at
        // all - a deploy, an outage, a ShedLock hold. One giant transaction
        // over all of it would hold order/payment/inventory row locks for
        // its entire duration, blocking live checkouts on those same rows,
        // and would lose the whole run's progress on a single failure.
        //
        // Each batch commits on its own instead (expireOneStalePayment is
        // REQUIRES_NEW), so locks are released continuously and a failure
        // costs one payment rather than the run.
        int processed = 0;
        for (int batch = 0; batch < maxExpiryBatchesPerRun; batch++) {
            List<Payment> stale = paymentRepository.findStaleForExpiry(
                    PaymentStatus.PENDING, PaymentMethod.UPI, cutoff,
                    org.springframework.data.domain.PageRequest.of(0, expiryBatchSize));

            if (stale.isEmpty()) {
                break;
            }

            for (Payment payment : stale) {
                // Through the proxy (see the `self` field) so REQUIRES_NEW
                // actually applies and each payment commits independently.
                self.expireOneStalePayment(payment.getId());
                processed++;
            }

            // A short batch means the query has drained - stop rather than
            // spending another round trip to confirm it.
            if (stale.size() < expiryBatchSize) {
                break;
            }
        }

        if (processed > 0) {
            log.info("Expired {} stale UPI payment(s) older than {} minutes", processed, upiTimeoutMinutes);
        }
    }

    /**
     * Expires exactly one stale payment, in its own transaction.
     *
     * Re-reads BOTH rows under their locks rather than trusting the sweep's
     * unlocked query result: between that query and this call, the customer
     * may have confirmed the payment, or cancelled the order, either of
     * which makes expiring it wrong. The re-check after locking is the
     * whole point - the pre-lock read is only a candidate list.
     *
     * Lock order is ORDER -> PAYMENT -> INVENTORY, identical to
     * cancelOrder's, so the two racing on the same order serialize on the
     * order row instead of deadlocking against each other.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void expireOneStalePayment(Long paymentId) {
        Payment payment = paymentRepository.findById(paymentId).orElse(null);
        if (payment == null || payment.getOrder() == null) {
            return;
        }
        Long orderId = payment.getOrder().getId();

        // ORDER first. restoreInventoryForOrder takes this same lock, and
        // taking it here first keeps a consistent acquisition order.
        Order order = orderRepository.findByIdForUpdate(orderId).orElse(null);
        if (order == null) {
            return;
        }

        // PAYMENT second, and re-read under the lock.
        Payment locked = paymentRepository.findByOrderIdForUpdate(orderId).orElse(null);
        if (locked == null
                || locked.getPaymentStatus() != PaymentStatus.PENDING
                || locked.getPaymentMethod() != PaymentMethod.UPI) {
            // Someone confirmed or cancelled it while this run was in
            // flight - their transition wins, this one is abandoned.
            return;
        }

        locked.setPaymentStatus(PaymentStatus.FAILED);
        paymentRepository.save(locked);

        // INVENTORY last. Returns false when the stock has already gone
        // back via cancellation - the flag, not this sweep, is what makes
        // that exactly-once. Only audit the restore that actually happened.
        boolean restored = orderService.restoreInventoryForOrder(orderId);

        auditLogService.log("UPI_PAYMENT_EXPIRED", "Payment", locked.getId(),
                "no confirmation within " + upiTimeoutMinutes + " minutes; "
                        + (restored ? "inventory restored" : "inventory already restored by another path"));
    }


    /**
     * Parses and validates a client-supplied payment method.
     *
     * Shared so placeOrder and initiatePayment cannot drift into accepting
     * different sets of values - which would mean an order could be created
     * with a method its own payment step then rejects.
     */
    public PaymentMethod parsePaymentMethod(String raw) {
        PaymentMethod method;
        try {
            method = PaymentMethod.valueOf(raw.toUpperCase());
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw new BadRequestException("Unknown payment method: " + raw);
        }
        if (method == PaymentMethod.ONLINE) {
            throw new BadRequestException("Card/online gateway payment is not available yet - use UPI or COD");
        }
        return method;
    }

    /**
     * The UPI deep link for an order, or null for COD.
     *
     * Worth being explicit about: this is pure local string building (see
     * UpiPaymentService.generatePaymentLink) - no gateway, no network call,
     * nothing that can block or fail. That is precisely why payment creation
     * can be folded into the order transaction without dragging an external
     * dependency into it. If a real gateway is ever introduced, THAT is the
     * point at which this must move back out.
     */
    public String upiLinkFor(Order order, PaymentMethod method) {
        return method == PaymentMethod.UPI
                ? upiPaymentService.generatePaymentLink(order.getOrderNumber(), order.getTotalAmount())
                : null;
    }

    /**
     * Creates the payment row for a freshly-placed order, from inside that
     * order's own transaction.
     *
     * Why this exists: checkout used to be two sequential HTTP requests -
     * POST /orders/place, wait, POST /payments, wait - so the customer paid
     * a full extra round trip (auth, rate-limit, routing and all) for what
     * is a single INSERT with no external dependency. Creating it here
     * removes that entire round trip from the critical path.
     *
     * Safe to put in the order transaction because it commits or rolls back
     * WITH the order: there is no window where an order exists without its
     * payment, which the two-request flow genuinely had (place succeeded,
     * payment call never arrived). uq_payments_order_id remains the real
     * guarantee of one payment per order.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public Payment createPaymentForNewOrder(Order order, PaymentMethod method) {
        Payment payment = new Payment();
        payment.setOrder(order);
        payment.setAmount(order.getTotalAmount());
        payment.setPaymentMethod(method);
        payment.setPaymentDate(LocalDateTime.now());
        payment.setActive(true);
        payment.setPaymentStatus(method == PaymentMethod.COD
                ? PaymentStatus.COD_PENDING
                : PaymentStatus.PENDING);
        return paymentRepository.save(payment);
    }

    /**
     * Acquires this order's row lock and then its payment's, in that fixed
     * order, and returns the payment re-read under its lock.
     *
     * Every mutating payment path goes through this. Two reasons:
     *
     * 1. Correctness. These methods all used to plain-read the payment,
     *    check its status, and write a new one. Two concurrent callers both
     *    read PENDING, both pass the check, and both write - so a
     *    double-submitted UPI confirmation produced two SUCCESS transitions
     *    and two audit entries. Re-reading under the lock means the second
     *    caller sees the first's committed status and fails its check.
     *
     * 2. Deadlock avoidance. completeCodPayment and confirmUpiPayment end
     *    by calling advanceOrderIfStillPending, which takes the ORDER lock -
     *    so they used to acquire PAYMENT then ORDER, the exact reverse of
     *    cancelOrder's ORDER then PAYMENT. Two of those racing on the same
     *    order could each hold what the other needed. Taking ORDER first
     *    here makes every path agree on the sequence
     *    ORDER -> PAYMENT -> INVENTORY.
     *
     * Re-acquiring the order lock later in the same transaction (as
     * advanceOrderIfStillPending does) is free - a transaction already
     * holding a row lock can take it again.
     */
    private Payment lockOrderThenPayment(Long orderId) {
        orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));

        return paymentRepository.findByOrderIdForUpdate(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found for this order"));
    }

    /**
     * Advances the order to CONFIRMED once payment is actually in hand - only
     * if it's still sitting in PENDING_CONFIRMATION. Prevents Payment and
     * Order from silently drifting out of sync (e.g. payment says SUCCESS
     * while the order is still stuck at "pending confirmation" forever).
     */
    private void advanceOrderIfStillPending(Order order) {
        if (order.getOrderStatus() == OrderStatus.PENDING_CONFIRMATION) {
            orderService.updateOrderStatus(order.getId(), OrderStatus.CONFIRMED);
        }
    }

    /**
     * Creates a payment record for an order the caller owns. Amount is always
     * taken from the order total and status is always computed here - a client
     * can never set either directly (that was the self-reported-payment bug).
     * Only COD and UPI are accepted right now - no card/wallet gateway is
     * wired up, so ONLINE is rejected rather than silently doing nothing.
     */
    @Transactional
    @io.micrometer.core.annotation.Timed(value = "payment.initiate", description = "Payment record creation (and UPI link generation)", percentiles = {0.5, 0.95, 0.99})
    public PaymentInitiationResponse initiatePayment(InitiatePaymentRequest request, Long callerCustomerId) {

        Order order = orderRepository.findById(request.getOrderId())
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));

        if (order.getCustomer() == null || !order.getCustomer().getId().equals(callerCustomerId)) {
            throw new ResourceNotFoundException("Order not found");
        }

        PaymentMethod method = parsePaymentMethod(request.getPaymentMethod());

        // IDEMPOTENT rather than an outright 409. placeOrder now creates the
        // payment inside the order transaction, so by the time a client
        // calls this endpoint the payment usually already exists - and older
        // app builds still call it unconditionally. Rejecting that with a
        // conflict would break checkout for every client that has not been
        // updated, for a request whose desired end state is already true.
        //
        // A MISMATCHED method is still a conflict: asking for UPI on an
        // order that already has a COD payment is a real disagreement about
        // what the customer is doing, not a retry, and silently returning
        // the wrong one would be worse than failing.
        Optional<Payment> existing = paymentRepository.findByOrderId(order.getId());
        if (existing.isPresent()) {
            Payment current = existing.get();
            if (current.getPaymentMethod() != method) {
                throw new ConflictException(
                        "This order already has a " + current.getPaymentMethod()
                                + " payment - it cannot be changed to " + method + ".");
            }
            return new PaymentInitiationResponse(current, upiLinkFor(order, method));
        }

        Payment payment = new Payment();
        payment.setOrder(order);
        payment.setAmount(order.getTotalAmount());
        payment.setPaymentMethod(method);
        payment.setPaymentDate(LocalDateTime.now());
        payment.setActive(true);
        payment.setPaymentStatus(method == PaymentMethod.COD
                ? PaymentStatus.COD_PENDING
                : PaymentStatus.PENDING);

        // The findByOrderId check above has a race window under concurrent
        // requests for the same order (double-tap, client retry-on-timeout).
        // uq_payments_order_id (V4 migration) is the real backstop - if two
        // requests both pass the check above, only one save() here succeeds;
        // the other hits this constraint violation and gets a clean 409
        // instead of a raw 500.
        Payment saved;
        try {
            saved = paymentRepository.save(payment);
        } catch (DataIntegrityViolationException ex) {
            throw new ConflictException("A payment already exists for this order");
        }

        return new PaymentInitiationResponse(saved, upiLinkFor(order, method));
    }

    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<com.gpstore.dto.response.PaymentResponse> getAllPayments(
            org.springframework.data.domain.Pageable pageable) {
        return paymentRepository.findAll(pageable)
                .map(com.gpstore.dto.response.PaymentResponse::from);
    }

    @Transactional(readOnly = true)
        public Optional<com.gpstore.dto.response.PaymentResponse> getPaymentByOrderId(Long orderId) {
            return paymentRepository.findByOrderId(orderId).map(com.gpstore.dto.response.PaymentResponse::from);
        }

    @Transactional(readOnly = true)
        public Optional<com.gpstore.dto.response.PaymentResponse> getPaymentByTransactionId(String transactionId) {
            return paymentRepository.findByTransactionId(transactionId).map(com.gpstore.dto.response.PaymentResponse::from);
        }

    @Transactional
    public com.gpstore.dto.response.PaymentResponse refundPayment(Long orderId) {

        Payment payment = lockOrderThenPayment(orderId);

        if (payment.getPaymentMethod() == PaymentMethod.COD &&
                payment.getPaymentStatus() == PaymentStatus.COD_PENDING) {
            throw new ConflictException("Refund is not required for unpaid COD order");
        }

        if (payment.getPaymentStatus() == PaymentStatus.REFUNDED) {
            throw new ConflictException("Payment is already refunded");
        }

        payment.setPaymentStatus(PaymentStatus.REFUND_PENDING);

        Payment saved = paymentRepository.save(payment);
        auditLogService.log("REFUND_INITIATED", "Payment", saved.getId(),
                "orderId=" + orderId + ", amount=" + saved.getAmount());
        return com.gpstore.dto.response.PaymentResponse.from(saved);
    }

    @Transactional
    public com.gpstore.dto.response.PaymentResponse completeRefund(Long orderId) {

        Payment payment = lockOrderThenPayment(orderId);

        if (payment.getPaymentStatus() != PaymentStatus.REFUND_PENDING) {
            throw new ConflictException("Payment is not waiting for refund");
        }

        payment.setPaymentStatus(PaymentStatus.REFUNDED);

        Payment saved = paymentRepository.save(payment);
        auditLogService.log("REFUND_COMPLETED", "Payment", saved.getId(),
                "orderId=" + orderId + ", amount=" + saved.getAmount());
        return com.gpstore.dto.response.PaymentResponse.from(saved);
    }

    @Transactional
    public com.gpstore.dto.response.PaymentResponse completeCodPayment(Long orderId) {

        Payment payment = lockOrderThenPayment(orderId);

        if (payment.getPaymentMethod() != PaymentMethod.COD) {
            throw new ConflictException("This payment is not a COD payment");
        }

        if (payment.getPaymentStatus() != PaymentStatus.COD_PENDING) {
            throw new ConflictException("COD payment is not pending");
        }

        payment.setPaymentStatus(PaymentStatus.COD_RECEIVED);
        payment.setPaymentDate(LocalDateTime.now());

        Payment saved = paymentRepository.save(payment);
        advanceOrderIfStillPending(saved.getOrder());
        auditLogService.log("COD_PAYMENT_COLLECTED", "Payment", saved.getId(),
                "orderId=" + orderId + ", amount=" + saved.getAmount());
        return com.gpstore.dto.response.PaymentResponse.from(saved);
    }

    /**
     * Manual confirmation that a UPI payment actually arrived - there's no
     * payment gateway webhook doing this automatically, since direct UPI (no
     * gateway) has no transaction fee but also no automated verification.
     * Whoever checks the shop's UPI app/bank notification confirms it here.
     */
    @Transactional
    public com.gpstore.dto.response.PaymentResponse confirmUpiPayment(Long orderId, String transactionId) {

        Payment payment = lockOrderThenPayment(orderId);

        if (payment.getPaymentMethod() != PaymentMethod.UPI) {
            throw new ConflictException("This payment is not a UPI payment");
        }

        if (payment.getPaymentStatus() != PaymentStatus.PENDING) {
            throw new ConflictException("This payment is not awaiting confirmation");
        }

        payment.setPaymentStatus(PaymentStatus.SUCCESS);
        payment.setPaymentDate(LocalDateTime.now());
        if (transactionId != null && !transactionId.isBlank()) {
            payment.setTransactionId(transactionId);
        }

        Payment saved;
        try {
            saved = paymentRepository.save(payment);
        } catch (DataIntegrityViolationException ex) {
            // Almost always means this exact UPI transaction ID was already
            // used to confirm a different payment - a real duplicate/replay,
            // not a random DB hiccup.
            throw new ConflictException("This transaction ID has already been used for another payment");
        }

        advanceOrderIfStillPending(saved.getOrder());

        auditLogService.log("UPI_PAYMENT_CONFIRMED", "Payment", saved.getId(),
                "orderId=" + orderId + ", amount=" + saved.getAmount()
                        + (transactionId != null ? ", txnId=" + transactionId : ""));
        return com.gpstore.dto.response.PaymentResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public Optional<com.gpstore.dto.response.PaymentResponse> getPaymentById(Long id) {
        return paymentRepository.findById(id).map(com.gpstore.dto.response.PaymentResponse::from);
    }
}
