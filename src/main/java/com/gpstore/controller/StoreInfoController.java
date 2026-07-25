package com.gpstore.controller;

import com.gpstore.dto.response.StoreInfoResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public - a customer needs this before they've even logged in (e.g. to contact support about a login problem). */
@RestController
public class StoreInfoController {

    private final String supportPhone;
    private final String supportWhatsapp;
    private final String supportEmail;

    public StoreInfoController(
            @Value("${store.support-phone}") String supportPhone,
            @Value("${store.support-whatsapp}") String supportWhatsapp,
            @Value("${store.support-email}") String supportEmail) {
        this.supportPhone = supportPhone;
        this.supportWhatsapp = supportWhatsapp;
        this.supportEmail = supportEmail;
    }

    @GetMapping("/api/store-info")
    public StoreInfoResponse getStoreInfo() {
        return new StoreInfoResponse(supportPhone, supportWhatsapp, supportEmail);
    }
}
