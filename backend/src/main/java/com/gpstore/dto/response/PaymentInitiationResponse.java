package com.gpstore.dto.response;

import com.gpstore.entity.Payment;

public class PaymentInitiationResponse {

    private final Payment payment;
    // Only set for UPI payments - null for COD (nothing to scan/click for COD).
    private final String upiPaymentLink;

    public PaymentInitiationResponse(Payment payment, String upiPaymentLink) {
        this.payment = payment;
        this.upiPaymentLink = upiPaymentLink;
    }

    public Payment getPayment() { return payment; }
    public String getUpiPaymentLink() { return upiPaymentLink; }
}
