package com.gpstore.security;

import com.gpstore.entity.Customer;
import com.gpstore.repository.CustomerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CustomerAccountStatusServiceTest {

    @Mock private CustomerRepository customerRepository;

    private CustomerAccountStatusService service;

    @BeforeEach
    void setUp() {
        service = new CustomerAccountStatusService(customerRepository);
    }

    @Test
    void activeEnabledCustomerIsUsable() {
        when(customerRepository.findById(7L)).thenReturn(Optional.of(customer(true, true)));

        assertTrue(service.isUsable(7L));
    }

    @Test
    void deactivatedCustomerIsNotUsableEvenWithALiveJwt() {
        when(customerRepository.findById(7L)).thenReturn(Optional.of(customer(false, true)));

        assertFalse(service.isUsable(7L));
    }

    @Test
    void disabledCustomerIsNotUsableEvenWhenActive() {
        when(customerRepository.findById(7L)).thenReturn(Optional.of(customer(true, false)));

        assertFalse(service.isUsable(7L));
    }

    @Test
    void missingCustomerIsNotUsable() {
        when(customerRepository.findById(7L)).thenReturn(Optional.empty());

        assertFalse(service.isUsable(7L));
    }

    @Test
    void nullCustomerIdIsNotUsable() {
        assertFalse(service.isUsable((Long) null));
    }

    @Test
    void invalidateDropsTheCacheSoReactivationIsImmediate() {
        when(customerRepository.findById(7L))
                .thenReturn(Optional.of(customer(false, true)))
                .thenReturn(Optional.of(customer(true, true)));

        assertFalse(service.isUsable(7L));
        service.invalidate(7L);
        assertTrue(service.isUsable(7L));
        verify(customerRepository, times(2)).findById(7L);
    }

    @Test
    void cachePreventsADatabaseHitWithinTheTtl() {
        when(customerRepository.findById(7L)).thenReturn(Optional.of(customer(true, true)));

        assertTrue(service.isUsable(7L));
        assertTrue(service.isUsable(7L));
        verify(customerRepository, times(1)).findById(7L);
    }

    @Test
    void roleMismatchIsDetectedAgainstTheLiveAccount() {
        assertFalse(CustomerAccountStatusService.roleMatches("DELIVERY_BOY", "CUSTOMER"));
        assertTrue(CustomerAccountStatusService.roleMatches("CUSTOMER", "CUSTOMER"));
        assertTrue(CustomerAccountStatusService.roleMatches(null, "CUSTOMER"));
    }

    private static Customer customer(boolean active, boolean enabled) {
        Customer customer = new Customer();
        customer.setId(7L);
        customer.setActive(active);
        customer.setEnabled(enabled);
        return customer;
    }
}
