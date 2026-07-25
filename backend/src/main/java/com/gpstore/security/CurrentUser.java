package com.gpstore.security;

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
}
