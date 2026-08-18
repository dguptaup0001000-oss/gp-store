package com.gpstore.service;

import com.gpstore.dto.AuthRequest;
import com.gpstore.dto.AuthResponse;
import com.gpstore.dto.RegisterRequest;
import com.gpstore.entity.Customer;
import com.gpstore.entity.Role;
import com.gpstore.exception.AuthException;
import com.gpstore.exception.BadRequestException;
import com.gpstore.exception.ConflictException;
import com.gpstore.exception.ResourceNotFoundException;
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
    private final OtpService otpService;

    public AuthService(CustomerRepository customerRepository,
                        JwtService jwtService,
                        PasswordEncoder passwordEncoder,
                        RefreshTokenService refreshTokenService,
                        OtpService otpService) {
        this.customerRepository = customerRepository;
        this.jwtService = jwtService;
        this.passwordEncoder = passwordEncoder;
        this.refreshTokenService = refreshTokenService;
        this.otpService = otpService;
    }

    @Transactional
    public AuthResponse register(RegisterRequest request) {

        customerRepository.findByEmail(request.getEmail()).ifPresent(existing -> {
            throw new ConflictException("An account with this email already exists");
        });
        // customers.mobile_number has the same unique constraint as email but
        // was missing the equivalent pre-check - a duplicate phone number
        // fell straight through to the DB constraint and surfaced as a raw,
        // unhandled 500 instead of a clear "already registered" message.
        customerRepository.findByMobileNumber(request.getPhone()).ifPresent(existing -> {
            throw new ConflictException("An account with this phone number already exists");
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

        // An OTP-only account (registered via phone, never set a password)
        // has no password hash to check against - reject with the same
        // generic message rather than throwing an NPE from
        // passwordEncoder.matches(..., null).
        if (customer.getPassword() == null || !passwordEncoder.matches(request.getPassword(), customer.getPassword())) {
            throw new AuthException(INVALID_CREDENTIALS);
        }

        return issueTokens(customer);
    }

    /** Sends an OTP to this phone number - works whether it's a new or existing customer. */
    public void sendLoginOtp(String mobileNumber) {
        otpService.sendOtp(mobileNumber);
    }

    /**
     * Verifies the OTP, then logs in the existing customer with this phone
     * number, OR auto-creates a bare-bones account if it's a new number -
     * the standard "OTP is both login and signup" pattern (same as
     * Swiggy/Zomato). No password is set on an auto-created account; it can
     * only ever be accessed via OTP unless the customer later sets one
     * through a profile/security settings flow.
     */
    @Transactional
    public AuthResponse verifyOtpAndAuthenticate(String mobileNumber, String otpCode) {
        otpService.verifyOtp(mobileNumber, otpCode);

        Customer customer = customerRepository.findByMobileNumber(mobileNumber)
                .orElseGet(() -> {
                    Customer newCustomer = new Customer();
                    newCustomer.setMobileNumber(mobileNumber);
                    newCustomer.setRole(Role.CUSTOMER);
                    newCustomer.setEnabled(true);
                    newCustomer.setVerified(true); // phone was just verified via OTP
                    newCustomer.setActive(true);
                    return customerRepository.save(newCustomer);
                });

        if (Boolean.FALSE.equals(customer.getActive())) {
            throw new AuthException("This account is not active");
        }

        return issueTokens(customer);
    }

    /**
     * Change password for a logged-in customer. If they don't have one yet
     * (an OTP-only account setting up password login for the first time),
     * no current password is required to verify - there's nothing to check
     * it against. If they already have one, the current password MUST
     * match, or anyone with a stolen access token could silently lock the
     * real owner out by changing it.
     */
    @Transactional
    public void changePassword(Long customerId, String currentPassword, String newPassword) {
        Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new ResourceNotFoundException("Customer not found"));

        if (customer.getPassword() != null) {
            if (currentPassword == null || !passwordEncoder.matches(currentPassword, customer.getPassword())) {
                throw new AuthException("Current password is incorrect");
            }
        } else if (customer.getEmail() == null) {
            // Password login is only meaningful with an email to log in
            // with - an account with neither would just create a password
            // nothing can ever use.
            throw new BadRequestException("Add an email to your account before setting a password");
        }

        customer.setPassword(passwordEncoder.encode(newPassword));
        customerRepository.save(customer);

        // Changing a password is a real security-sensitive event - force
        // every OTHER session to re-authenticate, same reasoning as
        // deactivating an account. This device's own session (the one that
        // just made this change) keeps working via its current tokens.
        refreshTokenService.revokeAllForCustomer(customerId);
    }

    /**
     * Forgot-password via OTP - reuses the existing rate-limited OTP send
     * endpoint (/api/auth/otp/send) and the same OtpService.verifyOtp used
     * for OTP login, just for a different purpose (proving phone ownership
     * to reset a password, not logging in via OTP directly). No email
     * infrastructure exists in this system - OTP is the real, working
     * verification mechanism already available, not a placeholder choice.
     */
    @Transactional
    public void resetPasswordWithOtp(String mobileNumber, String otpCode, String newPassword) {
        otpService.verifyOtp(mobileNumber, otpCode);

        Customer customer = customerRepository.findByMobileNumber(mobileNumber)
                .orElseThrow(() -> new ResourceNotFoundException("No account found for this mobile number"));

        customer.setPassword(passwordEncoder.encode(newPassword));
        customerRepository.save(customer);

        refreshTokenService.revokeAllForCustomer(customer.getId());
    }


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
