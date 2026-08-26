package com.gpstore.controller;

import com.gpstore.config.PlaceholderValues;
import com.gpstore.dto.response.StoreInfoResponse;
import com.gpstore.payment.gateway.CashfreeProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public - a customer needs this before they've even logged in (e.g. to contact support about a login problem). */
@RestController
public class StoreInfoController {

    private final String supportPhone;
    private final String supportWhatsapp;
    private final String supportEmail;
    private final String supportUrl;
    private final String upiId;
    private final CashfreeProperties cashfreeProperties;

    public StoreInfoController(
            @Value("${store.support-phone:}") String supportPhone,
            @Value("${store.support-whatsapp:}") String supportWhatsapp,
            @Value("${store.support-email:}") String supportEmail,
            @Value("${store.support-url:}") String supportUrl,
            @Value("${store.upi-id:}") String upiId,
            CashfreeProperties cashfreeProperties) {
        this.supportPhone = supportPhone;
        this.supportWhatsapp = supportWhatsapp;
        this.supportEmail = supportEmail;
        this.supportUrl = supportUrl;
        this.upiId = upiId;
        this.cashfreeProperties = cashfreeProperties;
    }

    @GetMapping("/api/store-info")
    public StoreInfoResponse getStoreInfo() {
        // Never hand a customer a published placeholder. Empty means
        // "not configured"; the app hides the row.
        return new StoreInfoResponse(
                PlaceholderValues.publicOrEmpty(supportPhone),
                PlaceholderValues.publicOrEmpty(supportWhatsapp),
                PlaceholderValues.publicOrEmpty(supportEmail),
                PlaceholderValues.publicOrEmpty(supportUrl),
                cashfreeProperties != null && cashfreeProperties.enabled(),
                !PlaceholderValues.isBlankOrPlaceholder(upiId));
    }
}
