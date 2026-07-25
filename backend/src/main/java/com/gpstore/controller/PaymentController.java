package com.gpstore.controller;

import com.gpstore.dto.request.InitiatePaymentRequest;
import com.gpstore.dto.response.PaymentInitiationResponse;
import com.gpstore.entity.Payment;
import com.gpstore.security.CurrentUser;
import com.gpstore.service.PaymentService;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    private final PaymentService paymentService;
    private final CurrentUser currentUser;

    public PaymentController(PaymentService paymentService, CurrentUser currentUser) {
        this.paymentService = paymentService;
        this.currentUser = currentUser;
    }

    // Starts a payment for an order the caller owns. Amount/status are always
    // computed server-side - the client can no longer set them (see PaymentService).
    // For UPI, the response includes a payment link/QR-source the app can render.
    @PostMapping
    public PaymentInitiationResponse createPayment(@Valid @RequestBody InitiatePaymentRequest request) {
        return paymentService.initiatePayment(request, currentUser.customerId());
    }

    // Admin only (enforced in SecurityConfig).
    @GetMapping
    public List<Payment> getAllPayments() {
        return paymentService.getAllPayments();
    }

    // Admin only (enforced in SecurityConfig).
    @GetMapping("/{id}")
    public Optional<Payment> getPaymentById(@PathVariable Long id) {
        return paymentService.getPaymentById(id);
    }

    // Admin only (enforced in SecurityConfig).
    @GetMapping("/order/{orderId}")
    public Optional<Payment> getPaymentByOrderId(@PathVariable Long orderId) {
        return paymentService.getPaymentByOrderId(orderId);
    }

    // Admin only (enforced in SecurityConfig).
    @GetMapping("/transaction/{transactionId}")
    public Optional<Payment> getPaymentByTransactionId(@PathVariable String transactionId) {
        return paymentService.getPaymentByTransactionId(transactionId);
    }

    // Admin only (enforced in SecurityConfig).
    @PutMapping("/order/{orderId}/refund")
    public Payment refundPayment(@PathVariable Long orderId) {
        return paymentService.refundPayment(orderId);
    }

    // Admin only (enforced in SecurityConfig).
    @PutMapping("/order/{orderId}/refund/complete")
    public Payment completeRefund(@PathVariable Long orderId) {
        return paymentService.completeRefund(orderId);
    }

    // Admin or delivery partner (enforced in SecurityConfig).
    @PutMapping("/order/{orderId}/cod/complete")
    public Payment completeCodPayment(@PathVariable Long orderId) {
        return paymentService.completeCodPayment(orderId);
    }

    // Admin only (enforced in SecurityConfig) - confirms a UPI payment
    // actually arrived, since there's no gateway webhook doing this
    // automatically for direct (fee-free) UPI.
    @PutMapping("/order/{orderId}/upi/confirm")
    public Payment confirmUpiPayment(
            @PathVariable Long orderId,
            @RequestParam(required = false) String transactionId) {
        return paymentService.confirmUpiPayment(orderId, transactionId);
    }
}
