package com.gpstore.service;

import com.gpstore.auth.IndianPhoneNumbers;
import com.gpstore.auth.OtpPurpose;
import com.gpstore.dto.AuthRequest;
import com.gpstore.dto.AuthResponse;
import com.gpstore.dto.PasswordResetTokenResponse;
import com.gpstore.dto.RegisterRequest;
import com.gpstore.entity.Customer;
import com.gpstore.entity.PasswordResetToken;
import com.gpstore.entity.Role;
import com.gpstore.exception.AuthException;
import com.gpstore.exception.BadRequestException;
import com.gpstore.exception.ConflictException;
import com.gpstore.exception.ResourceNotFoundException;
import com.gpstore.repository.CustomerRepository;
import com.gpstore.repository.PasswordResetTokenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private static final String INVALID_CREDENTIALS = "Invalid email or password";
    private static final String GENERIC_OTP_REQUEST = OtpService.GENERIC_REQUEST_MESSAGE;
    private static final String INVALID_RESET_TOKEN = "Invalid or expired reset token";

    private final CustomerRepository customerRepository;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenService refreshTokenService;
    private final OtpService otpService;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;
    private final SecureRandom secureRandom = new SecureRandom();
    private final int passwordResetTokenMinutes;

    public AuthService(CustomerRepository customerRepository,
                        JwtService jwtService,
                        PasswordEncoder passwordEncoder,
                        RefreshTokenService refreshTokenService,
                        OtpService otpService,
                        PasswordResetTokenRepository passwordResetTokenRepository,
                        ObjectProvider<Clock> clocks,
                        PlatformTransactionManager transactionManager,
                        @Value("${otp.password-reset-token-minutes:10}") int passwordResetTokenMinutes) {
        this.customerRepository = customerRepository;
        this.jwtService = jwtService;
        this.passwordEncoder = passwordEncoder;
        this.refreshTokenService = refreshTokenService;
        this.otpService = otpService;
        this.passwordResetTokenRepository = passwordResetTokenRepository;
        this.clock = clocks.getIfAvailable(Clock::systemDefaultZone);
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.passwordResetTokenMinutes = Math.max(1, passwordResetTokenMinutes);
    }

    @Transactional
    public AuthResponse register(RegisterRequest request) {

        // Same wording for email and phone collisions on purpose: telling the
        // caller WHICH identifier is taken is account enumeration. A person
        // who mistyped their own details still gets a conflict they can act
        // on (try a different email or phone) without confirming to an
        // attacker that a specific address is registered.
        if (customerRepository.findByEmail(request.getEmail()).isPresent()
                || customerRepository.findByMobileNumber(request.getPhone()).isPresent()) {
            throw new ConflictException(
                    "Unable to create this account. Try a different email or phone.");
        }

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

    public AuthResponse login(AuthRequest request) {

        Customer customer = customerRepository
                .findByEmail(request.getEmail())
                .orElseThrow(() -> new AuthException(INVALID_CREDENTIALS));

        if (Boolean.FALSE.equals(customer.getActive())) {
            throw new AuthException(INVALID_CREDENTIALS);
        }

        String storedHash = customer.getPassword();
        if (storedHash == null || !passwordEncoder.matches(request.getPassword(), storedHash)) {
            throw new AuthException(INVALID_CREDENTIALS);
        }

        return issueTokens(customer);
    }

    /** Sends an OTP to this phone number - works whether it's a new or existing customer. */
    public void sendLoginOtp(String mobileNumber) {
        otpService.sendOtp(mobileNumber);
    }

    public String requestLoginOtp(String phone) {
        otpService.requestOtp(phone, OtpPurpose.LOGIN, true);
        return GENERIC_OTP_REQUEST;
    }

    /**
     * Verifies the OTP, then logs in the existing customer with this phone
     * number, OR auto-creates a bare-bones account if it's a new number -
     * the standard "OTP is both login and signup" pattern (same as
     * Swiggy/Zomato). No password is set on an auto-created account; it can
     * only ever be accessed via OTP unless the customer later sets one
     * through a profile/security settings flow.
     */
    public AuthResponse verifyOtpAndAuthenticate(String mobileNumber, String otpCode) {
        String local10 = IndianPhoneNumbers.toLocal10(mobileNumber);
        otpService.verifyOtp(mobileNumber, otpCode, OtpPurpose.LOGIN);

        return transactionTemplate.execute(status -> {
            Customer customer = findByPhone(local10)
                    .orElseGet(() -> {
                        Customer newCustomer = new Customer();
                        newCustomer.setMobileNumber(local10);
                        newCustomer.setRole(Role.CUSTOMER);
                        newCustomer.setEnabled(true);
                        newCustomer.setVerified(true);
                        newCustomer.setActive(true);
                        return customerRepository.save(newCustomer);
                    });

            if (Boolean.FALSE.equals(customer.getActive())) {
                throw new AuthException("This account is not active");
            }

            return issueTokens(customer);
        });
    }

    public String requestPasswordResetOtp(String phone) {
        String local10 = IndianPhoneNumbers.toLocal10(phone);
        boolean deliver = findByPhone(local10).isPresent();
        otpService.requestOtp(phone, OtpPurpose.PASSWORD_RESET, deliver);
        return GENERIC_OTP_REQUEST;
    }

    public PasswordResetTokenResponse verifyPasswordResetOtp(String phone, String otp) {
        otpService.verifyOtp(phone, otp, OtpPurpose.PASSWORD_RESET);
        String local10 = IndianPhoneNumbers.toLocal10(phone);

        return transactionTemplate.execute(status -> {
            Customer customer = findByPhone(local10)
                    .orElseThrow(() -> new BadRequestException(OtpService.INVALID_OTP_MESSAGE));

            byte[] randomBytes = new byte[32];
            secureRandom.nextBytes(randomBytes);
            String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);

            PasswordResetToken entity = new PasswordResetToken();
            entity.setCustomer(customer);
            entity.setTokenHash(sha256(rawToken));
            entity.setCreatedAt(LocalDateTime.now(clock));
            entity.setExpiresAt(LocalDateTime.now(clock).plusMinutes(passwordResetTokenMinutes));
            passwordResetTokenRepository.save(entity);

            return new PasswordResetTokenResponse(rawToken, passwordResetTokenMinutes * 60L);
        });
    }

    @Transactional
    public void completePasswordReset(String rawResetToken, String newPassword) {
        if (newPassword == null || newPassword.length() < 8) {
            throw new BadRequestException("Password must be at least 8 characters");
        }
        PasswordResetToken token = passwordResetTokenRepository.findByTokenHash(sha256(rawResetToken))
                .orElseThrow(() -> new BadRequestException(INVALID_RESET_TOKEN));

        if (token.getConsumedAt() != null || token.getExpiresAt().isBefore(LocalDateTime.now(clock))) {
            throw new BadRequestException(INVALID_RESET_TOKEN);
        }

        Customer customer = token.getCustomer();
        customer.setPassword(passwordEncoder.encode(newPassword));
        customerRepository.save(customer);

        token.setConsumedAt(LocalDateTime.now(clock));
        passwordResetTokenRepository.save(token);
        passwordResetTokenRepository.consumeAllOpenForCustomer(customer.getId(), LocalDateTime.now(clock));

        if (customer.getMobileNumber() != null) {
            otpService.consumeOpenChallenges(customer.getMobileNumber(), OtpPurpose.PASSWORD_RESET);
        }
        refreshTokenService.revokeAllForCustomer(customer.getId());
        log.info("PASSWORD_RESET_SUCCESS phone={}", IndianPhoneNumbers.mask(customer.getMobileNumber()));
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
            throw new BadRequestException("Add an email to your account before setting a password");
        }

        customer.setPassword(passwordEncoder.encode(newPassword));
        customerRepository.save(customer);

        refreshTokenService.revokeAllForCustomer(customerId);
    }

    /**
     * Legacy combined reset. Requires a PASSWORD_RESET challenge (not LOGIN).
     * Does not reveal whether the account exists — invalid OTP is the only
     * public failure.
     */
    public void resetPasswordWithOtp(String mobileNumber, String otpCode, String newPassword) {
        try {
            otpService.verifyOtp(mobileNumber, otpCode, OtpPurpose.PASSWORD_RESET);
        } catch (BadRequestException ex) {
            throw new BadRequestException(OtpService.INVALID_OTP_MESSAGE);
        }

        transactionTemplate.executeWithoutResult(status -> {
            Optional<Customer> customer = findByPhone(mobileNumber);
            if (customer.isEmpty()) {
                throw new BadRequestException(OtpService.INVALID_OTP_MESSAGE);
            }
            if (newPassword == null || newPassword.length() < 8) {
                throw new BadRequestException("Password must be at least 8 characters");
            }

            customer.get().setPassword(passwordEncoder.encode(newPassword));
            customerRepository.save(customer.get());
            refreshTokenService.revokeAllForCustomer(customer.get().getId());
            log.info("PASSWORD_RESET_SUCCESS phone={}", IndianPhoneNumbers.mask(mobileNumber));
        });
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

    /** Logs out of every device/session for this customer. */
    @Transactional
    public void logoutAllSessions(Long customerId) {
        refreshTokenService.revokeAllForCustomer(customerId);
    }

    private Optional<Customer> findByPhone(String rawPhone) {
        String local10 = IndianPhoneNumbers.toLocal10(rawPhone);
        return customerRepository.findByMobileNumber(local10)
                .or(() -> customerRepository.findByMobileNumber(IndianPhoneNumbers.normalizeTo91(rawPhone)));
    }

    private AuthResponse issueTokens(Customer customer) {
        String accessToken = jwtService.generateToken(customer.getId(), customer.getEmail(), customer.getRole());
        String refreshToken = refreshTokenService.issue(customer);
        return new AuthResponse(accessToken, refreshToken, customer.getId(), customer.getEmail(), customer.getRole().name());
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
