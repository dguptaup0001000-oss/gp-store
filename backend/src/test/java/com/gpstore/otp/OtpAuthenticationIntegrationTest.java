package com.gpstore.otp;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.gpstore.auth.OtpPurpose;
import com.gpstore.dto.AuthResponse;
import com.gpstore.dto.PasswordResetTokenResponse;
import com.gpstore.entity.Customer;
import com.gpstore.entity.OtpVerification;
import com.gpstore.entity.Role;
import com.gpstore.exception.BadRequestException;
import com.gpstore.exception.TooManyRequestsException;
import com.gpstore.repository.CustomerRepository;
import com.gpstore.repository.OtpVerificationRepository;
import com.gpstore.service.AuthService;
import com.gpstore.service.OtpService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "msg91.enabled=false",
        "otp.sms-sending-enabled=false",
        "otp.resend-cooldown-seconds=45",
        "otp.max-sends-per-window=3",
        "otp.send-window-minutes=10",
        "otp.expiry-minutes=5",
        "otp.max-verify-attempts=5",
        "outbox.initial-delay-ms=3600000",
        "outbox.drain-interval-ms=3600000",
        "payment.expiry-initial-delay-ms=3600000",
        "idempotency.cleanup-initial-delay-ms=3600000",
        "otp.cleanup-initial-delay-ms=3600000",
        "delivery.late-flag-initial-delay-ms=3600000"
})
@AutoConfigureMockMvc
class OtpAuthenticationIntegrationTest {

    @Autowired private OtpService otpService;
    @Autowired private AuthService authService;
    @Autowired private OtpVerificationRepository otpRepository;
    @Autowired private CustomerRepository customerRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private MockMvc mockMvc;
    @MockitoSpyBean private OtpProvider otpProvider;

    private String uniquePhone() {
        return "98" + String.format("%08d", Math.floorMod(System.nanoTime(), 100_000_000L));
    }

    private String peek(String phone, OtpPurpose purpose) {
        return otpProvider.peekIssuedOtpForTests(
                com.gpstore.auth.IndianPhoneNumbers.normalizeTo91(phone), purpose)
                .orElseThrow();
    }

    private Customer existingCustomer(String phone) {
        Customer customer = new Customer();
        customer.setFullName("OTP Tester");
        customer.setEmail("otp-" + UUID.randomUUID() + "@example.com");
        customer.setMobileNumber(phone);
        customer.setPassword(passwordEncoder.encode("OldPassw0rd"));
        customer.setRole(Role.CUSTOMER);
        customer.setEnabled(true);
        customer.setVerified(true);
        customer.setActive(true);
        return customerRepository.save(customer);
    }

    @Test
    @DisplayName("valid login OTP issues the existing JWT login response")
    void loginOtpIssuesExistingJwt() {
        String phone = uniquePhone();
        existingCustomer(phone);
        otpService.requestOtp(phone, OtpPurpose.LOGIN, true);
        AuthResponse response = authService.verifyOtpAndAuthenticate(phone, peek(phone, OtpPurpose.LOGIN));
        assertNotNull(response.getToken());
        assertNotNull(response.getRefreshToken());
        assertEquals(Role.CUSTOMER.name(), response.getRole());
    }

    @Test
    @DisplayName("unknown phone still auto-creates on LOGIN verify, matching existing architecture")
    void unknownPhoneLoginCreatesAccount() {
        String phone = uniquePhone();
        otpService.requestOtp(phone, OtpPurpose.LOGIN, true);
        AuthResponse response = authService.verifyOtpAndAuthenticate(phone, peek(phone, OtpPurpose.LOGIN));
        assertTrue(customerRepository.findByMobileNumber(phone).isPresent());
        assertNotNull(response.getToken());
    }

    @Test
    @DisplayName("wrong OTP and consumed OTP are refused")
    void wrongAndConsumedOtp() {
        String phone = uniquePhone();
        otpService.requestOtp(phone, OtpPurpose.LOGIN, true);
        String code = peek(phone, OtpPurpose.LOGIN);
        assertThrows(BadRequestException.class, () -> otpService.verifyOtp(phone, "000000", OtpPurpose.LOGIN));
        otpService.verifyOtp(phone, code, OtpPurpose.LOGIN);
        assertThrows(BadRequestException.class, () -> otpService.verifyOtp(phone, code, OtpPurpose.LOGIN));
    }

    @Test
    @DisplayName("LOGIN OTP cannot satisfy PASSWORD_RESET and the reverse")
    void purposeIsSeparated() {
        String phone = uniquePhone();
        existingCustomer(phone);
        otpService.requestOtp(phone, OtpPurpose.LOGIN, true);
        String loginOtp = peek(phone, OtpPurpose.LOGIN);
        assertThrows(BadRequestException.class,
                () -> otpService.verifyOtp(phone, loginOtp, OtpPurpose.PASSWORD_RESET));

        otpService.requestOtp(phone, OtpPurpose.PASSWORD_RESET, true);
        String resetOtp = peek(phone, OtpPurpose.PASSWORD_RESET);
        assertNotEquals(loginOtp, resetOtp);
        assertThrows(BadRequestException.class,
                () -> otpService.verifyOtp(phone, resetOtp, OtpPurpose.LOGIN));
        otpService.verifyOtp(phone, resetOtp, OtpPurpose.PASSWORD_RESET);
    }

    @Test
    @DisplayName("expired challenges and attempt caps are enforced")
    void expiryAndMaxAttempts() {
        String phone = uniquePhone();
        otpService.requestOtp(phone, OtpPurpose.LOGIN, true);
        OtpVerification row = otpRepository
                .findFirstByMobileNumberAndPurposeAndVerifiedFalseAndConsumedAtIsNullOrderByCreatedAtDesc(
                        phone, OtpPurpose.LOGIN)
                .orElseThrow();
        row.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        otpRepository.save(row);
        assertThrows(BadRequestException.class,
                () -> otpService.verifyOtp(phone, peek(phone, OtpPurpose.LOGIN), OtpPurpose.LOGIN));

        String phone2 = uniquePhone();
        otpService.requestOtp(phone2, OtpPurpose.LOGIN, true);
        String good = peek(phone2, OtpPurpose.LOGIN);
        for (int i = 0; i < 5; i++) {
            assertThrows(BadRequestException.class, () -> otpService.verifyOtp(phone2, "000000", OtpPurpose.LOGIN));
        }
        assertThrows(BadRequestException.class, () -> otpService.verifyOtp(phone2, good, OtpPurpose.LOGIN));
    }

    @Test
    @DisplayName("resend cooldown does not send a second SMS")
    void resendCooldownIsIdempotent() {
        String phone = uniquePhone();
        otpService.requestOtp(phone, OtpPurpose.LOGIN, true);
        otpService.requestOtp(phone, OtpPurpose.LOGIN, true);
        verify(otpProvider, times(1)).send(anyString(), any(), any(), any());
    }

    @Test
    @DisplayName("phone send window is enforced")
    void sendWindowRateLimit() {
        String phone = uniquePhone();
        otpService.requestOtp(phone, OtpPurpose.LOGIN, true);
        // Cooldown blocks further real sends; bump last_sent_at into the past so the window limit is what fires.
        otpRepository.findFirstByMobileNumberAndPurposeAndVerifiedFalseAndConsumedAtIsNullOrderByCreatedAtDesc(
                        phone, OtpPurpose.LOGIN)
                .ifPresent(row -> {
                    row.setLastSentAt(LocalDateTime.now().minusMinutes(2));
                    otpRepository.save(row);
                });
        otpService.requestOtp(phone, OtpPurpose.LOGIN, true);
        otpRepository.findFirstByMobileNumberAndPurposeAndVerifiedFalseAndConsumedAtIsNullOrderByCreatedAtDesc(
                        phone, OtpPurpose.LOGIN)
                .ifPresent(row -> {
                    row.setLastSentAt(LocalDateTime.now().minusMinutes(2));
                    otpRepository.save(row);
                });
        otpService.requestOtp(phone, OtpPurpose.LOGIN, true);
        otpRepository.findAll().stream()
                .filter(o -> phone.equals(o.getMobileNumber()))
                .forEach(row -> {
                    row.setLastSentAt(LocalDateTime.now().minusMinutes(2));
                    otpRepository.save(row);
                });
        assertThrows(TooManyRequestsException.class,
                () -> otpService.requestOtp(phone, OtpPurpose.LOGIN, true));
    }

    @Test
    @DisplayName("password reset request never reveals whether the phone is registered")
    void passwordResetDoesNotEnumerateAccounts() {
        String unknown = uniquePhone();
        String known = uniquePhone();
        existingCustomer(known);
        String unknownMsg = authService.requestPasswordResetOtp(unknown);
        String knownMsg = authService.requestPasswordResetOtp(known);
        assertEquals(unknownMsg, knownMsg);
        assertEquals(OtpService.GENERIC_REQUEST_MESSAGE, unknownMsg);
        assertFalse(otpProvider.peekIssuedOtpForTests(
                com.gpstore.auth.IndianPhoneNumbers.normalizeTo91(unknown), OtpPurpose.PASSWORD_RESET).isPresent());
        assertTrue(otpProvider.peekIssuedOtpForTests(
                com.gpstore.auth.IndianPhoneNumbers.normalizeTo91(known), OtpPurpose.PASSWORD_RESET).isPresent());
    }

    @Test
    @DisplayName("a deactivated account cannot reset its password")
    void deactivatedAccountCannotResetPassword() {
        String phone = uniquePhone();
        Customer customer = existingCustomer(phone);
        customer.setActive(false);
        customerRepository.save(customer);

        String msg = authService.requestPasswordResetOtp(phone);
        assertEquals(OtpService.GENERIC_REQUEST_MESSAGE, msg);
        assertFalse(otpProvider.peekIssuedOtpForTests(
                com.gpstore.auth.IndianPhoneNumbers.normalizeTo91(phone), OtpPurpose.PASSWORD_RESET).isPresent(),
                "a banned phone must not receive a reset SMS");

        otpService.requestOtp(phone, OtpPurpose.PASSWORD_RESET, true);
        assertThrows(BadRequestException.class,
                () -> authService.verifyPasswordResetOtp(phone, peek(phone, OtpPurpose.PASSWORD_RESET)));
        assertTrue(passwordEncoder.matches("OldPassw0rd",
                customerRepository.findById(customer.getId()).orElseThrow().getPassword()));
    }

    @Test
    @DisplayName("password reset verify/complete hashes the new password and refuses token reuse")
    void passwordResetCompletesAndCannotReuseToken() {
        String phone = uniquePhone();
        Customer customer = existingCustomer(phone);
        authService.requestPasswordResetOtp(phone);
        PasswordResetTokenResponse token = authService.verifyPasswordResetOtp(
                phone, peek(phone, OtpPurpose.PASSWORD_RESET));
        authService.completePasswordReset(token.getResetToken(), "BrandNewPass9");
        Customer reloaded = customerRepository.findById(customer.getId()).orElseThrow();
        assertTrue(passwordEncoder.matches("BrandNewPass9", reloaded.getPassword()));
        assertFalse(passwordEncoder.matches("OldPassw0rd", reloaded.getPassword()));
        assertThrows(BadRequestException.class,
                () -> authService.completePasswordReset(token.getResetToken(), "AnotherPass99"));
        assertThrows(BadRequestException.class,
                () -> authService.completePasswordReset(token.getResetToken(), "short"));
    }

    @Test
    @DisplayName("password policy is enforced on complete")
    void passwordPolicyOnComplete() {
        String phone = uniquePhone();
        existingCustomer(phone);
        authService.requestPasswordResetOtp(phone);
        PasswordResetTokenResponse token = authService.verifyPasswordResetOtp(
                phone, peek(phone, OtpPurpose.PASSWORD_RESET));
        assertThrows(BadRequestException.class,
                () -> authService.completePasswordReset(token.getResetToken(), "short"));
        assertTrue(passwordEncoder.matches("OldPassw0rd",
                customerRepository.findByMobileNumber(phone).orElseThrow().getPassword()));
    }

    @Test
    @DisplayName("a password-reset token cannot authenticate")
    void resetTokenIsNotASession() throws Exception {
        String phone = uniquePhone();
        existingCustomer(phone);
        authService.requestPasswordResetOtp(phone);
        PasswordResetTokenResponse token = authService.verifyPasswordResetOtp(
                phone, peek(phone, OtpPurpose.PASSWORD_RESET));
        mockMvc.perform(get("/api/orders/my-orders")
                        .header("Authorization", "Bearer " + token.getResetToken()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("new auth endpoints validate input and hide provider internals")
    void controllerContracts() throws Exception {
        mockMvc.perform(post("/api/auth/otp/login/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"not-a-phone\"}"))
                .andExpect(status().isBadRequest());

        String unknown = uniquePhone();
        mockMvc.perform(post("/api/auth/password-reset/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"" + unknown + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(OtpService.GENERIC_REQUEST_MESSAGE));

        mockMvc.perform(post("/api/auth/otp/login/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"" + unknown + "\",\"otp\":\"123456\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(OtpService.INVALID_OTP_MESSAGE));
    }

    @Test
    @DisplayName("OTP values and auth keys never appear in logs")
    void sensitiveValuesAreNotLogged() {
        Logger otpLog = (Logger) LoggerFactory.getLogger(OtpService.class);
        Logger mockLog = (Logger) LoggerFactory.getLogger(MockOtpProvider.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        otpLog.addAppender(appender);
        mockLog.addAppender(appender);
        try {
            String phone = uniquePhone();
            otpService.requestOtp(phone, OtpPurpose.LOGIN, true);
            String code = peek(phone, OtpPurpose.LOGIN);
            otpService.verifyOtp(phone, code, OtpPurpose.LOGIN);
            String joined = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .reduce("", (a, b) -> a + "\n" + b);
            assertFalse(joined.contains(code), "OTP was logged");
            assertFalse(joined.contains("MSG91_AUTH_KEY"));
            assertFalse(joined.contains(phone), "full phone was logged");
            assertTrue(joined.contains("OTP_REQUESTED"));
            assertTrue(joined.contains("OTP_VERIFY_SUCCESS"));
        } finally {
            otpLog.detachAppender(appender);
            mockLog.detachAppender(appender);
        }
    }
}
