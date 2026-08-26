package com.gpstore.dto.response;

public class StoreInfoResponse {

    private final String supportPhone;
    private final String supportWhatsapp;
    private final String supportEmail;
    private final String supportUrl;
    private final boolean onlinePaymentEnabled;
    private final boolean upiConfigured;

    public StoreInfoResponse(
            String supportPhone,
            String supportWhatsapp,
            String supportEmail,
            String supportUrl,
            boolean onlinePaymentEnabled,
            boolean upiConfigured) {
        this.supportPhone = supportPhone;
        this.supportWhatsapp = supportWhatsapp;
        this.supportEmail = supportEmail;
        this.supportUrl = supportUrl;
        this.onlinePaymentEnabled = onlinePaymentEnabled;
        this.upiConfigured = upiConfigured;
    }

    public String getSupportPhone() { return supportPhone; }
    public String getSupportWhatsapp() { return supportWhatsapp; }
    public String getSupportEmail() { return supportEmail; }
    public String getSupportUrl() { return supportUrl; }
    public boolean isOnlinePaymentEnabled() { return onlinePaymentEnabled; }
    public boolean isUpiConfigured() { return upiConfigured; }
}
