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

    private final PaymentRepository paymentRepository;
    private final OrderRepository orderRepository;
    private final AuditLogService auditLogService;
    private final UpiPaymentService upiPaymentService;
    private final OrderService orderService;
    private final int upiTimeoutMinutes;

    public PaymentService(
            PaymentRepository paymentRepository,
            OrderRepository orderRepository,
            AuditLogService auditLogService,
            UpiPaymentService upiPaymentService,
            OrderService orderService,
            @org.springframework.beans.factory.annotation.Value("${payment.upi-timeout-minutes}") int upiTimeoutMinutes) {

        this.paymentRepository = paymentRepository;
        this.orderRepository = orderRepository;
        this.auditLogService = auditLogService;
        this.upiPaymentService = upiPaymentService;
        this.orderService = orderService;
        this.upiTimeoutMinutes = upiTimeoutMinutes;
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
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 5 * 60 * 1000)
    @net.javacrumbs.shedlock.spring.annotation.SchedulerLock(
            name = "expireStalePendingUpiPayments",
            lockAtMostFor = "10m",
            lockAtLeastFor = "1m")
    @Transactional
    public void expireStalePendingUpiPayments() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(upiTimeoutMinutes);

        List<Payment> stale = paymentRepository
                .findByPaymentStatusAndPaymentMethodAndPaymentDateBefore(
                        PaymentStatus.PENDING, PaymentMethod.UPI, cutoff);

        for (Payment payment : stale) {
            payment.setPaymentStatus(PaymentStatus.FAILED);
            paymentRepository.save(payment);

            // Give back the stock reserved for this order at checkout time.
            // Previously missing - an abandoned/never-confirmed UPI payment
            // permanently cost the store that stock, since only the Payment
            // record was ever touched here, never the Order's inventory.
            // Reuses the exact same locked restore path cancelOrder() uses,
            // so a concurrent fresh checkout on the same variant can't race
            // this into an inconsistent count.
            if (payment.getOrder() != null) {
                orderService.restoreInventoryForOrder(payment.getOrder().getId());
            }

            auditLogService.log("UPI_PAYMENT_EXPIRED", "Payment", payment.getId(),
                    "no confirmation within " + upiTimeoutMinutes + " minutes; inventory restored");
        }
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
    public PaymentInitiationResponse initiatePayment(InitiatePaymentRequest request, Long callerCustomerId) {

        Order order = orderRepository.findById(request.getOrderId())
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));

        if (order.getCustomer() == null || !order.getCustomer().getId().equals(callerCustomerId)) {
            throw new ResourceNotFoundException("Order not found");
        }

        if (paymentRepository.findByOrderId(order.getId()).isPresent()) {
            throw new ConflictException("A payment already exists for this order");
        }

        PaymentMethod method;
        try {
            method = PaymentMethod.valueOf(request.getPaymentMethod().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new BadRequestException("Unknown payment method: " + request.getPaymentMethod());
        }

        if (method == PaymentMethod.ONLINE) {
            throw new BadRequestException("Card/online gateway payment is not available yet - use UPI or COD");
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

        Payment saved = paymentRepository.save(payment);

        String upiLink = method == PaymentMethod.UPI
                ? upiPaymentService.generatePaymentLink(order.getOrderNumber(), order.getTotalAmount())
                : null;

        return new PaymentInitiationResponse(saved, upiLink);
    }

    public List<com.gpstore.dto.response.PaymentResponse> getAllPayments() {
        return paymentRepository.findAll().stream()
                .map(com.gpstore.dto.response.PaymentResponse::from)
                .toList();
    }

    public Optional<Payment> getPaymentByOrderId(Long orderId) {
        return paymentRepository.findByOrderId(orderId);
    }

    public Optional<Payment> getPaymentByTransactionId(String transactionId) {
        return paymentRepository.findByTransactionId(transactionId);
    }

    @Transactional
    public Payment refundPayment(Long orderId) {

        Payment payment = paymentRepository.findByOrderId(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found for this order"));

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
        return saved;
    }

    @Transactional
    public Payment completeRefund(Long orderId) {

        Payment payment = paymentRepository.findByOrderId(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found for this order"));

        if (payment.getPaymentStatus() != PaymentStatus.REFUND_PENDING) {
            throw new ConflictException("Payment is not waiting for refund");
        }

        payment.setPaymentStatus(PaymentStatus.REFUNDED);

        Payment saved = paymentRepository.save(payment);
        auditLogService.log("REFUND_COMPLETED", "Payment", saved.getId(),
                "orderId=" + orderId + ", amount=" + saved.getAmount());
        return saved;
    }

    @Transactional
    public Payment completeCodPayment(Long orderId) {

        Payment payment = paymentRepository.findByOrderId(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found for this order"));

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
        return saved;
    }

    /**
     * Manual confirmation that a UPI payment actually arrived - there's no
     * payment gateway webhook doing this automatically, since direct UPI (no
     * gateway) has no transaction fee but also no automated verification.
     * Whoever checks the shop's UPI app/bank notification confirms it here.
     */
    @Transactional
    public Payment confirmUpiPayment(Long orderId, String transactionId) {

        Payment payment = paymentRepository.findByOrderId(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found for this order"));

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
        return saved;
    }

    public Optional<Payment> getPaymentById(Long id) {
        return paymentRepository.findById(id);
    }
}
