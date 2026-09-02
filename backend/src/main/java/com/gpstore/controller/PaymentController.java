package com.gpstore.controller;

import com.gpstore.dto.request.InitiatePaymentRequest;
import com.gpstore.dto.response.PaymentInitiationResponse;
import com.gpstore.entity.Payment;
import com.gpstore.security.CurrentUser;
import com.gpstore.service.PaymentService;

import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    private static final Logger log = LoggerFactory.getLogger(PaymentController.class);

    private final PaymentService paymentService;
    private final CurrentUser currentUser;
    private final com.gpstore.payment.GatewayPaymentService gatewayPaymentService;

    public PaymentController(PaymentService paymentService, CurrentUser currentUser,
                             com.gpstore.payment.GatewayPaymentService gatewayPaymentService) {
        this.gatewayPaymentService = gatewayPaymentService;
        this.paymentService = paymentService;
        this.currentUser = currentUser;
    }

    // Starts a payment for an order the caller owns. Amount/status are always
    // computed server-side - the client can no longer set them (see PaymentService).
    // For UPI, the response includes a payment link/QR-source the app can render.
    //
    // Timed at INFO - this is the second of two sequential calls checkout
    // makes right after the "Place Order" countdown, and how long it takes
    // is exactly what determines how long the customer stares at a spinner.
    @PostMapping
    public PaymentInitiationResponse createPayment(@Valid @RequestBody InitiatePaymentRequest request) {
        long start = System.currentTimeMillis();
        try {
            return paymentService.initiatePayment(request, currentUser.customerId());
        } finally {
            log.info("POST /api/payments took {} ms (orderId={})", System.currentTimeMillis() - start, request.getOrderId());
        }
    }

    // Admin only (enforced in SecurityConfig).
    @GetMapping
    public Page<com.gpstore.dto.response.PaymentResponse> getAllPayments(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, Math.min(size, 100));
        return paymentService.getAllPayments(pageable);
    }

    // Admin only (enforced in SecurityConfig).
    @GetMapping("/{id}")
    public Optional<com.gpstore.dto.response.PaymentResponse> getPaymentById(@PathVariable Long id) {
        return paymentService.getPaymentById(id);
    }

    // Admin only (enforced in SecurityConfig).
    @GetMapping("/order/{orderId}")
    public Optional<com.gpstore.dto.response.PaymentResponse> getPaymentByOrderId(@PathVariable Long orderId) {
        return paymentService.getPaymentByOrderId(orderId);
    }

    // Admin only (enforced in SecurityConfig).
    @GetMapping("/transaction/{transactionId}")
    public Optional<com.gpstore.dto.response.PaymentResponse> getPaymentByTransactionId(@PathVariable String transactionId) {
        return paymentService.getPaymentByTransactionId(transactionId);
    }

    // Admin only (enforced in SecurityConfig).
    /**
     * Starts a gateway checkout for an order this customer owns.
     *
     * TAKES NO AMOUNT, and that is the security property rather than an
     * omission - there is no field on this request a client could use to
     * influence what gets charged. The figure sent to Cashfree is read from
     * the order row the backend itself computed.
     *
     * Safe to call twice: an already-paid order is refused with a conflict
     * rather than issued a second session.
     */
    @PostMapping("/order/{orderId}/checkout-session")
    public com.gpstore.dto.response.GatewayCheckoutResponse startCheckout(@PathVariable Long orderId) {
        return gatewayPaymentService.startCheckout(orderId, currentUser.customerId());
    }

    /**
     * Asks the gateway what really happened, and applies it.
     *
     * The recovery path. The app calls this when it returns from checkout -
     * however it returns, including cancelled or after being killed - and
     * whenever an order is opened later. It is what makes a lost webhook
     * survivable, and what lets the app avoid polling: the answer comes from
     * Cashfree's servers, so the client's opinion is never consulted.
     */
    @PostMapping("/order/{orderId}/verify")
    public java.util.Map<String, String> verify(@PathVariable Long orderId) {
        return java.util.Map.of(
                "paymentStatus", gatewayPaymentService.reconcile(orderId, currentUser.customerId()).name());
    }

    @PutMapping("/order/{orderId}/refund")
    public com.gpstore.dto.response.PaymentResponse refundPayment(@PathVariable Long orderId) {
        return paymentService.refundPayment(orderId);
    }

    // Admin only (enforced in SecurityConfig).
    @PutMapping("/order/{orderId}/refund/complete")
    public com.gpstore.dto.response.PaymentResponse completeRefund(@PathVariable Long orderId) {
        return paymentService.completeRefund(orderId);
    }

    // Admin or delivery partner (enforced in SecurityConfig).
    @PutMapping("/order/{orderId}/cod/complete")
    public com.gpstore.dto.response.PaymentResponse completeCodPayment(@PathVariable Long orderId) {
        boolean isAdmin = "ADMIN".equals(currentUser.get().getRole());
        return paymentService.completeCodPayment(orderId, currentUser.get().getWorkerId(), isAdmin);
    }

    // Admin only (enforced in SecurityConfig) - confirms a UPI payment
    // actually arrived, since there's no gateway webhook doing this
    // automatically for direct (fee-free) UPI.
    @PutMapping("/order/{orderId}/upi/confirm")
    public com.gpstore.dto.response.PaymentResponse confirmUpiPayment(
            @PathVariable Long orderId,
            @RequestParam(required = false) String transactionId) {
        return paymentService.confirmUpiPayment(orderId, transactionId);
    }
}
