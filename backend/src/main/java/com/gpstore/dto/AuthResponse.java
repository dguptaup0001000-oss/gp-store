package com.gpstore.dto;

public class AuthResponse {

    private final String token;
    private final String refreshToken;
    private final Long customerId;
    private final String email;
    private final String role;
    private final boolean mustChangePassword;

    public AuthResponse(String token, String refreshToken, Long customerId, String email, String role) {
        this(token, refreshToken, customerId, email, role, false);
    }

    /**
     * @param mustChangePassword this account is still on a password somebody
     *     else chose (a one-time password handed over by the platform), so the
     *     app must send the operator to the change-password screen before
     *     anything else.
     *
     *     WHY THE LOGIN RESPONSE CARRIES IT. JwtFilter refuses every route but
     *     /api/auth/change-password while the flag is set - including
     *     /api/customers/me. So the app cannot ASK whether it owes a password
     *     change; it has to be TOLD, here, in the one response it is allowed
     *     to receive.
     */
    public AuthResponse(String token,
                        String refreshToken,
                        Long customerId,
                        String email,
                        String role,
                        boolean mustChangePassword) {
        this.token = token;
        this.refreshToken = refreshToken;
        this.customerId = customerId;
        this.email = email;
        this.role = role;
        this.mustChangePassword = mustChangePassword;
    }

    public String getToken() { return token; }
    public String getRefreshToken() { return refreshToken; }
    public Long getCustomerId() { return customerId; }
    public String getEmail() { return email; }
    public String getRole() { return role; }
    public boolean isMustChangePassword() { return mustChangePassword; }
}
