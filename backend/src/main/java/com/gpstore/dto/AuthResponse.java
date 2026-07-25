package com.gpstore.dto;

public class AuthResponse {

    private final String token;
    private final String refreshToken;
    private final Long customerId;
    private final String email;
    private final String role;

    public AuthResponse(String token, String refreshToken, Long customerId, String email, String role) {
        this.token = token;
        this.refreshToken = refreshToken;
        this.customerId = customerId;
        this.email = email;
        this.role = role;
    }

    public String getToken() { return token; }
    public String getRefreshToken() { return refreshToken; }
    public Long getCustomerId() { return customerId; }
    public String getEmail() { return email; }
    public String getRole() { return role; }
}
