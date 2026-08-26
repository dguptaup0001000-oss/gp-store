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

    /** False when STORE_UPI_ID is missing or a published placeholder. */
    public boolean configured() {
        return !com.gpstore.config.PlaceholderValues.isBlankOrPlaceholder(upiId);
    }

    public String generatePaymentLink(String orderNumber, BigDecimal amount) {
        if (!configured()) {
            throw new com.gpstore.exception.ConflictException(
                    "UPI is not configured. Set STORE_UPI_ID to the shop's real VPA.");
        }
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
