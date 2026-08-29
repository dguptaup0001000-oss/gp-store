package com.gpstore.perf;

import com.gpstore.auth.OtpPurpose;
import com.gpstore.exception.TooManyRequestsException;
import com.gpstore.otp.OtpProvider;
import com.gpstore.repository.OtpVerificationRepository;
import com.gpstore.service.OtpService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * The SMS provider is not allowed to hold a database connection.
 *
 * WHAT WAS WRONG. OtpService.sendOtp was @Transactional and called MSG91 on
 * the last line of it. MSG91 is a third party over the public internet with,
 * at the time, a ten-second connect timeout and a ten-second request timeout -
 * so one slow send held a pooled connection for up to twenty seconds. The pool
 * is ten connections wide. Ten people tapping "send code" during an MSG91
 * slowdown took the whole application down, not just login: browse, search and
 * checkout all wait on the same ten connections.
 *
 * Nothing about that is visible at the call site, which is why it needs a test
 * and not a comment. The rate limit and the row are still transactional; only
 * the network call moved out.
 */
@SpringBootTest(properties = {
        "otp.channel=SMS",
        "otp.sms-sending-enabled=false",
        "msg91.enabled=false",
        "otp.resend-cooldown-seconds=0",
        "outbox.initial-delay-ms=3600000",
        "outbox.drain-interval-ms=3600000",
        "payment.expiry-initial-delay-ms=3600000",
        "idempotency.cleanup-initial-delay-ms=3600000",
        "otp.cleanup-initial-delay-ms=3600000",
        "delivery.late-flag-initial-delay-ms=3600000"
})
@DisplayName("A slow SMS provider cannot hold a database connection")
class OtpSendBoundaryTest {

    @Autowired private OtpService otpService;
    @Autowired private OtpVerificationRepository otpRepository;
    @MockitoSpyBean private OtpProvider otpProvider;

    private static String freshNumber() {
        return "9" + String.valueOf(System.nanoTime()).substring(0, 9);
    }

    @Test
    @DisplayName("the provider is called with no transaction open")
    void smsIsSentOutsideTheTransaction() {
        AtomicBoolean sawTransaction = new AtomicBoolean(true);

        doAnswer(invocation -> {
            sawTransaction.set(TransactionSynchronizationManager.isActualTransactionActive());
            return OtpProvider.SendResult.ok("test");
        }).when(otpProvider).send(anyString(), any(OtpPurpose.class), any(), any());

        otpService.sendOtp(freshNumber());

        assertFalse(sawTransaction.get(),
                "MSG91 was called with a transaction open, which means a pooled database connection is "
                        + "held for as long as MSG91 takes to answer. That is how one slow third party "
                        + "exhausts a ten-connection pool and takes the entire shop offline.");
    }

    @Test
    @DisplayName("the code is committed before it is texted, never after")
    void theRowIsCommittedFirst() {
        String number = freshNumber();
        AtomicBoolean rowVisible = new AtomicBoolean(false);

        doAnswer(invocation -> {
            rowVisible.set(otpRepository
                    .findFirstByMobileNumberAndVerifiedFalseOrderByCreatedAtDesc(number)
                    .isPresent());
            return OtpProvider.SendResult.ok("test");
        }).when(otpProvider).send(anyString(), any(OtpPurpose.class), any(), any());

        otpService.sendOtp(number);

        assertTrue(rowVisible.get(),
                "The OTP row was not committed at the moment the SMS went out.");
    }

    @Test
    @DisplayName("a rate-limited request texts nobody")
    void rateLimitStillBlocksBeforeAnySend() {
        String number = freshNumber();

        for (int i = 0; i < 20; i++) {
            try {
                otpService.sendOtp(number);
            } catch (TooManyRequestsException stopHere) {
                break;
            }
        }

        int sendsBefore = org.mockito.Mockito.mockingDetails(otpProvider).getInvocations().size();
        assertThrows(TooManyRequestsException.class, () -> otpService.sendOtp(number),
                "The rate limit stopped being enforced.");
        int sendsAfter = org.mockito.Mockito.mockingDetails(otpProvider).getInvocations().size();

        assertEquals(sendsBefore, sendsAfter,
                "A rate-limited request still reached the SMS provider - that is a bill, not a bug report.");
    }

    @Test
    @DisplayName("sendOtp does not carry @Transactional")
    void theBoundaryIsWhereItLooks() {
        var method = assertDoesNotThrow(() -> OtpService.class.getMethod("sendOtp", String.class));
        assertNull(method.getAnnotation(org.springframework.transaction.annotation.Transactional.class),
                "sendOtp is @Transactional again, which puts the MSG91 call back inside the transaction.");
        verify(otpProvider, never()).send(null, null, null, null);
    }
}
