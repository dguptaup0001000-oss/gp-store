package com.gpstore.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Convenience accessor for the currently authenticated user.
 * Use this instead of trusting a customerId passed in from the client.
 */
@Component
public class CurrentUser {

    public AuthenticatedUser get() {
        Object principal = SecurityContextHolder.getContext()
                .getAuthentication() != null
                ? SecurityContextHolder.getContext().getAuthentication().getPrincipal()
                : null;

        if (!(principal instanceof AuthenticatedUser)) {
            throw new IllegalStateException("No authenticated user in security context");
        }

        return (AuthenticatedUser) principal;
    }

    public Long customerId() {
        return get().getCustomerId();
    }

    /**
     * Does the caller hold this permission?
     *
     * THE QUESTION THIS REPLACES, at six call sites:
     *
     *     boolean isAdmin = "ADMIN".equals(currentUser.get().getRole());
     *
     * getRole() is the raw role name from the JWT, so that matched exactly one
     * role. SUPER_ADMIN - the shop owner - did not match it. Neither did
     * MANAGER, ORDER_MANAGER, DELIVERY_MANAGER or SUPPORT. Each of them fell
     * through to the customer ownership check, was compared against their own
     * customer id, and was told the order did not exist. A support agent, whose
     * entire job is looking orders up, could not look an order up.
     *
     * Asking about the PERMISSION instead of the role name is also the only
     * version that stays correct when a role is added or its permissions are
     * edited: RolePermissions already decides who holds what, and SecurityConfig
     * already gates every route this way. These call sites were the outliers.
     *
     * Read from the granted authorities rather than the role, because JwtFilter
     * derives those from the live account on every request - a demotion takes
     * effect immediately instead of when the token expires.
     */
    public boolean has(AdminPermission permission) {
        Authentication authentication =
                SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return false;
        }
        String required = permission.authority();
        for (GrantedAuthority granted : authentication.getAuthorities()) {
            if (required.equals(granted.getAuthority())) {
                return true;
            }
        }
        return false;
    }
}
