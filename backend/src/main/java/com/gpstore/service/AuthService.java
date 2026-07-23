package com.gpstore.service;

import com.gpstore.dto.AuthRequest;
import com.gpstore.dto.AuthResponse;
import com.gpstore.dto.RegisterRequest;
import com.gpstore.entity.Customer;
import com.gpstore.entity.Role;
import com.gpstore.exception.AuthException;
import com.gpstore.exception.ConflictException;
import com.gpstore.repository.CustomerRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private static final String INVALID_CREDENTIALS = "Invalid email or password";

    private final CustomerRepository customerRepository;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenService refreshTokenService;

    public AuthService(CustomerRepository customerRepository,
                        JwtService jwtService,
                        PasswordEncoder passwordEncoder,
                        RefreshTokenService refreshTokenService) {
        this.customerRepository = customerRepository;
        this.jwtService = jwtService;
        this.passwordEncoder = passwordEncoder;
        this.refreshTokenService = refreshTokenService;
    }

    @Transactional
    public AuthResponse register(RegisterRequest request) {

        customerRepository.findByEmail(request.getEmail()).ifPresent(existing -> {
            throw new ConflictException("An account with this email already exists");
        });

        Customer customer = new Customer();
        customer.setFullName(request.getName());
        customer.setEmail(request.getEmail());
        customer.setMobileNumber(request.getPhone());
        customer.setPassword(passwordEncoder.encode(request.getPassword()));
        customer.setRole(Role.CUSTOMER);
        customer.setEnabled(true);
        customer.setVerified(false);
        customer.setActive(true);

        customer = customerRepository.save(customer);

        return issueTokens(customer);
    }

    @Transactional
    public AuthResponse login(AuthRequest request) {

        Customer customer = customerRepository
                .findByEmail(request.getEmail())
                .orElseThrow(() -> new AuthException(INVALID_CREDENTIALS));

        if (Boolean.FALSE.equals(customer.getActive())) {
            // Same generic message on purpose - don't reveal account state to an unauthenticated caller.
            throw new AuthException(INVALID_CREDENTIALS);
        }

        if (!passwordEncoder.matches(request.getPassword(), customer.getPassword())) {
            throw new AuthException(INVALID_CREDENTIALS);
        }

        return issueTokens(customer);
    }

    /**
     * Exchanges a valid refresh token for a new access token + a NEW refresh
     * token (rotation - the old refresh token stops working the moment this
     * succeeds, see RefreshTokenService for why).
     */
    @Transactional
    public AuthResponse refresh(String rawRefreshToken) {
        RefreshTokenService.RotationResult result = refreshTokenService.validateAndRotate(rawRefreshToken);
        Customer customer = result.customer();

        String accessToken = jwtService.generateToken(customer.getId(), customer.getEmail(), customer.getRole());

        return new AuthResponse(accessToken, result.newRawToken(), customer.getId(), customer.getEmail(), customer.getRole().name());
    }

    /** Logs out of just this session/device. */
    public void logout(String rawRefreshToken) {
        refreshTokenService.revoke(rawRefreshToken);
    }

    /** Logs out of every device/session for this customer - e.g. after a password change or "sign out everywhere". */
    @Transactional
    public void logoutAllSessions(Long customerId) {
        refreshTokenService.revokeAllForCustomer(customerId);
    }

    private AuthResponse issueTokens(Customer customer) {
        String accessToken = jwtService.generateToken(customer.getId(), customer.getEmail(), customer.getRole());
        String refreshToken = refreshTokenService.issue(customer);
        return new AuthResponse(accessToken, refreshToken, customer.getId(), customer.getEmail(), customer.getRole().name());
    }
}
