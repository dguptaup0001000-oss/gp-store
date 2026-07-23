package com.gpstore.security;

/**
 * The authenticated principal placed into the SecurityContext by JwtFilter.
 * Carries the identity extracted from the validated JWT so services never
 * need to trust a customerId supplied by the client in a path/body.
 */
public class AuthenticatedUser {

    private final Long customerId;
    private final String email;
    private final String role;

    public AuthenticatedUser(Long customerId, String email, String role) {
        this.customerId = customerId;
        this.email = email;
        this.role = role;
    }

    public Long getCustomerId() {
        return customerId;
    }

    public String getEmail() {
        return email;
    }

    public String getRole() {
        return role;
    }
}
