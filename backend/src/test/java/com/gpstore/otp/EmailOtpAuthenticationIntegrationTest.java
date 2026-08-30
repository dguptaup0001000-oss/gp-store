package com.gpstore.otp;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.gpstore.auth.OtpPurpose;
import com.gpstore.dto.AuthResponse;
import com.gpstore.entity.Customer;
import com.gpstore.entity.OtpVerification;
import com.gpstore.entity.Role;
import com.gpstore.exception.BadRequestException;
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
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "otp.channel=EMAIL",
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
class EmailOtpAuthenticationIntegrationTest {

    @Autowired private OtpService otpService;
    @Autowired private AuthService authService;
    @Autowired private OtpVerificationRepository otpRepository;
    @Autowired private CustomerRepository customerRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private OtpProvider otpProvider;
    @Autowired private MockMvc mockMvc;

    private String uniqueEmail() {
        return "otp-" + UUID.randomUUID() + "@example.com";
    }

    private String peek(String email, OtpPurpose purpose) {
        return otpProvider.peekIssuedOtpForTests(email, purpose).orElseThrow();
    }

    private Customer existingCustomer(String email) {
        Customer customer = new Customer();
        customer.setFullName("Email OTP Tester");
        customer.setEmail(email);
        customer.setMobileNumber("98" + String.format("%08d", Math.floorMod(System.nanoTime(), 100_000_000L)));
        customer.setPassword(passwordEncoder.encode("OldPassw0rd"));
        customer.setRole(Role.CUSTOMER);
        customer.setEnabled(true);
        customer.setVerified(true);
        customer.setActive(true);
        return customerRepository.save(customer);
    }

    @Test
    @DisplayName("email OTP login issues the existing JWT pair")
    void emailLoginIssuesJwt() {
        String email = uniqueEmail();
        existingCustomer(email);
        otpService.requestOtp(email, OtpPurpose.LOGIN, true);
        AuthResponse response = authService.verifyOtpAndAuthenticate(email, peek(email, OtpPurpose.LOGIN));
        assertNotNull(response.getToken());
        assertEquals(email, response.getEmail());
    }

    @Test
    @DisplayName("wrong email OTP increments the persisted attempt counter")
    void wrongCodeIncrementsAndPersists() {
        String email = uniqueEmail();
        existingCustomer(email);
        otpService.requestOtp(email, OtpPurpose.LOGIN, true);
        assertThrows(BadRequestException.class, () -> otpService.verifyOtp(email, "000000", OtpPurpose.LOGIN));
        OtpVerification row = otpRepository
                .findFirstByMobileNumberAndPurposeAndVerifiedFalseAndConsumedAtIsNullOrderByCreatedAtDesc(
                        email, OtpPurpose.LOGIN)
                .orElseThrow();
        assertEquals(1, row.getAttempts());
        assertFalse(Boolean.TRUE.equals(row.getVerified()));
    }

    @Test
    @DisplayName("brute-force cap holds after max failed verifies")
    void bruteForceCapHolds() {
        String email = uniqueEmail();
        otpService.requestOtp(email, OtpPurpose.LOGIN, true);
        String good = peek(email, OtpPurpose.LOGIN);
        for (int i = 0; i < 5; i++) {
            assertThrows(BadRequestException.class, () -> otpService.verifyOtp(email, "000000", OtpPurpose.LOGIN));
        }
        OtpVerification row = otpRepository
                .findFirstByMobileNumberAndPurposeAndVerifiedFalseAndConsumedAtIsNullOrderByCreatedAtDesc(
                        email, OtpPurpose.LOGIN)
                .orElseThrow();
        assertEquals(5, row.getAttempts());
        assertThrows(BadRequestException.class, () -> otpService.verifyOtp(email, good, OtpPurpose.LOGIN));
    }

    @Test
    @DisplayName("expired email OTP is refused")
    void expiryHolds() {
        String email = uniqueEmail();
        otpService.requestOtp(email, OtpPurpose.LOGIN, true);
        OtpVerification row = otpRepository
                .findFirstByMobileNumberAndPurposeAndVerifiedFalseAndConsumedAtIsNullOrderByCreatedAtDesc(
                        email, OtpPurpose.LOGIN)
                .orElseThrow();
        row.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        otpRepository.save(row);
        assertThrows(BadRequestException.class,
                () -> otpService.verifyOtp(email, peek(email, OtpPurpose.LOGIN), OtpPurpose.LOGIN));
    }

    @Test
    @DisplayName("OTP value is never logged")
    void otpIsNeverLogged() {
        Logger otpLog = (Logger) LoggerFactory.getLogger(OtpService.class);
        Logger emailLog = (Logger) LoggerFactory.getLogger(EmailOtpProvider.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        otpLog.addAppender(appender);
        emailLog.addAppender(appender);
        try {
            String email = uniqueEmail();
            otpService.requestOtp(email, OtpPurpose.LOGIN, true);
            String code = peek(email, OtpPurpose.LOGIN);
            otpService.verifyOtp(email, code, OtpPurpose.LOGIN);
            String joined = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .reduce("", (a, b) -> a + "\n" + b);
            assertFalse(joined.contains(code), "OTP was logged");
            assertFalse(joined.contains(email), "full email was logged");
            assertFalse(joined.contains("SMTP_PASSWORD"));
        } finally {
            otpLog.detachAppender(appender);
            emailLog.detachAppender(appender);
        }
    }

    @Test
    @DisplayName("HTTP login request accepts email and hides enumeration")
    void controllerAcceptsEmail() throws Exception {
        String email = uniqueEmail();
        existingCustomer(email);
        mockMvc.perform(post("/api/auth/otp/login/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(OtpService.GENERIC_REQUEST_MESSAGE));
    }
}
