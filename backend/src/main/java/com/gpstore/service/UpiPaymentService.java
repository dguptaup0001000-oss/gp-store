package com.gpstore.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.net.URLEncoder;

@Service
public class UpiPaymentService {

    private final String upiId;
    private final String payeeName;

    public UpiPaymentService(
            @Value("${store.upi-id}") String upiId,
            @Value("${store.upi-payee-name}") String payeeName) {
        this.upiId = upiId;
        this.payeeName = payeeName;
    }

    /**
     * Standard UPI deep-link format - any UPI app (GPay, PhonePe, Paytm, etc.)
     * can open this directly, or your Flutter app can render it as a QR code.
     * "tr" (transaction reference) is the order number, so a payment can be
     * matched back to the order manually when confirming it arrived.
     */
    public String generatePaymentLink(String orderNumber, BigDecimal amount) {
        String encodedName = URLEncoder.encode(payeeName, StandardCharsets.UTF_8);
        String encodedNote = URLEncoder.encode("Order " + orderNumber, StandardCharsets.UTF_8);

        return "upi://pay?pa=" + upiId
                + "&pn=" + encodedName
                + "&am=" + amount.toPlainString()
                + "&cu=INR"
                + "&tr=" + orderNumber
                + "&tn=" + encodedNote;
    }
}
