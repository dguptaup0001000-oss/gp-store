package com.gpstore.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("Customer identity is never inferred from a standalone worker")
class CurrentUserCustomerBoundaryTest {

    private final CurrentUser currentUser = new CurrentUser();

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void standaloneWorkerHasNoCustomerId() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        new AuthenticatedUser(null, "worker@example.test",
                                "DELIVERY_BOY", 41L),
                        null, List.of(new SimpleGrantedAuthority("ROLE_DELIVERY_BOY"))));

        assertThrows(AccessDeniedException.class, currentUser::customerId);
    }

    @Test
    void customerRiderStillUsesTheirRealCustomerRelationship() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        new AuthenticatedUser(23L, "rider@example.test",
                                "DELIVERY_BOY"),
                        null, List.of(
                                new SimpleGrantedAuthority("ROLE_CUSTOMER"),
                                new SimpleGrantedAuthority("ROLE_DELIVERY_BOY"))));

        assertEquals(23L, currentUser.customerId());
    }
}
