package com.gpstore.security;

import com.gpstore.entity.Customer;
import com.gpstore.entity.RefreshToken;
import com.gpstore.entity.Role;
import com.gpstore.exception.AuthException;
import com.gpstore.repository.CustomerRepository;
import com.gpstore.repository.RefreshTokenRepository;
import com.gpstore.service.RefreshTokenService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {
        "outbox.initial-delay-ms=3600000",
        "outbox.drain-interval-ms=3600000",
        "payment.expiry-initial-delay-ms=3600000",
        "idempotency.cleanup-initial-delay-ms=3600000",
        "otp.cleanup-initial-delay-ms=3600000",
        "delivery.late-flag-initial-delay-ms=3600000"
})
class RefreshTokenReuseDetectionTest {

    @Autowired private RefreshTokenService refreshTokenService;
    @Autowired private CustomerRepository customerRepository;
    @Autowired private RefreshTokenRepository refreshTokenRepository;

    @Test
    @DisplayName("replaying a rotated refresh token revokes the live successor")
    void reuseOfARevokedTokenKillsTheFamily() {
        Customer customer = new Customer();
        customer.setFullName("Reuse Probe");
        customer.setEmail("refresh-reuse-" + System.nanoTime() + "@example.com");
        customer.setMobileNumber("8" + String.valueOf(System.nanoTime()).substring(0, 9));
        customer.setPassword("not-used-here");
        customer.setRole(Role.CUSTOMER);
        customer.setEnabled(true);
        customer.setActive(true);
        customer = customerRepository.save(customer);
        final Long customerId = customer.getId();

        String first = refreshTokenService.issue(customer);
        RefreshTokenService.RotationResult rotated = refreshTokenService.validateAndRotate(first);
        String successor = rotated.newRawToken();

        assertThrows(AuthException.class, () -> refreshTokenService.validateAndRotate(first));

        assertThrows(AuthException.class, () -> refreshTokenService.validateAndRotate(successor),
                "the rotated token must die with the family, otherwise theft keeps a session");

        assertTrue(refreshTokenRepository.findAll().stream()
                        .filter(t -> t.getCustomer().getId().equals(customerId))
                        .allMatch(t -> Boolean.TRUE.equals(t.getRevoked())),
                "no live refresh token should remain for this customer after reuse");
    }
}
