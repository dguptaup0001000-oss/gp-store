package com.gpstore.dto.request;

import jakarta.validation.constraints.NotBlank;

/**
 * What the app sends on startup / on Firebase token refresh to register
 * this device for push notifications (see CustomerController.updateMyFcmToken).
 */
public class FcmTokenRequest {

    @NotBlank(message = "fcmToken is required")
    private String fcmToken;

    public String getFcmToken() { return fcmToken; }
    public void setFcmToken(String fcmToken) { this.fcmToken = fcmToken; }
}
