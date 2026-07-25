package com.gpstore.dto.response;

public class StoreInfoResponse {

    private final String supportPhone;
    private final String supportWhatsapp;
    private final String supportEmail;

    public StoreInfoResponse(String supportPhone, String supportWhatsapp, String supportEmail) {
        this.supportPhone = supportPhone;
        this.supportWhatsapp = supportWhatsapp;
        this.supportEmail = supportEmail;
    }

    public String getSupportPhone() { return supportPhone; }
    public String getSupportWhatsapp() { return supportWhatsapp; }
    public String getSupportEmail() { return supportEmail; }
}
