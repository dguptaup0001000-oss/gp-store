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

    /**
     * Set only for a worker-app session, and null for everyone else.
     *
     * A WORKER IS NOT A CUSTOMER ROW ANY MORE. Their credentials live on the
     * delivery_partners record, so a worker token carries the roster id
     * directly instead of a customerId that has to be translated back through
     * an account link. customerId is null for these sessions - deliberately,
     * so anything that reaches for it on a worker request fails loudly
     * instead of silently acting on the wrong person.
     */
    private final Long workerId;

    public AuthenticatedUser(Long customerId, String email, String role) {
        this(customerId, email, role, null);
    }

    public AuthenticatedUser(Long customerId, String email, String role, Long workerId) {
        this.customerId = customerId;
        this.email = email;
        this.role = role;
        this.workerId = workerId;
    }

    public Long getWorkerId() {
        return workerId;
    }

    public boolean isWorkerSession() {
        return workerId != null;
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
