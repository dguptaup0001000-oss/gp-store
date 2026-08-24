package com.gpstore.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public class PasswordResetTokenResponse {

    @JsonProperty("reset_token")
    private final String resetToken;

    @JsonProperty("expires_in_seconds")
    private final long expiresInSeconds;

    public PasswordResetTokenResponse(String resetToken, long expiresInSeconds) {
        this.resetToken = resetToken;
        this.expiresInSeconds = expiresInSeconds;
    }

    public String getResetToken() {
        return resetToken;
    }

    public long getExpiresInSeconds() {
        return expiresInSeconds;
    }
}
