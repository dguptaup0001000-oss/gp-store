package com.gpstore.perf;

import com.gpstore.exception.BadRequestException;
import com.gpstore.repository.OtpVerificationRepository;
import com.gpstore.service.OtpService;
import com.gpstore.service.SmsService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
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
        // No real SMS is sent regardless - this is the shipped default and is
        // restated here so the test cannot depend on ambient configuration.
        "otp.sms-sending-enabled=false",
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
    @MockitoSpyBean private SmsService smsService;

    private static String freshNumber() {
        return "9" + String.valueOf(System.nanoTime()).substring(0, 9);
    }

    @Test
    @DisplayName("the provider is called with no transaction open")
    void smsIsSentOutsideTheTransaction() {
        AtomicBoolean sawTransaction = new AtomicBoolean(true);

        doAnswer(invocation -> {
            // Asked from inside the call itself, which is the only moment the
            // question means anything: this is where the ten seconds would go.
            sawTransaction.set(TransactionSynchronizationManager.isActualTransactionActive());
            return null;
        }).when(smsService).sendOtp(anyString(), anyString());

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
            // A separate read, outside the transaction that wrote it. If the
            // row is visible here it has committed - so any code that reaches
            // a phone is a code this service can verify. The reverse order
            // texts somebody a code the database then rolls back.
            rowVisible.set(otpRepository
                    .findFirstByMobileNumberAndVerifiedFalseOrderByCreatedAtDesc(number)
                    .isPresent());
            return null;
        }).when(smsService).sendOtp(anyString(), anyString());

        otpService.sendOtp(number);

        assertTrue(rowVisible.get(),
                "The OTP row was not committed at the moment the SMS went out.");
    }

    @Test
    @DisplayName("a rate-limited request texts nobody")
    void rateLimitStillBlocksBeforeAnySend() {
        String number = freshNumber();

        // Whatever the configured window allows, exceed it.
        for (int i = 0; i < 20; i++) {
            try {
                otpService.sendOtp(number);
            } catch (BadRequestException stopHere) {
                break;
            }
        }

        // The rate limit runs inside the transaction and throws before the
        // TransactionTemplate returns, so the send below it never happens.
        // Moving the network call out must not have moved it out from behind
        // the guard as well - each send costs real money.
        int sendsBefore = org.mockito.Mockito.mockingDetails(smsService).getInvocations().size();
        assertThrows(BadRequestException.class, () -> otpService.sendOtp(number),
                "The rate limit stopped being enforced.");
        int sendsAfter = org.mockito.Mockito.mockingDetails(smsService).getInvocations().size();

        assertEquals(sendsBefore, sendsAfter,
                "A rate-limited request still reached the SMS provider - that is a bill, not a bug report.");
    }

    @Test
    @DisplayName("sendOtp does not carry @Transactional")
    void theBoundaryIsWhereItLooks() {
        // Belt and braces on the behavioural tests above: the regression that
        // reintroduces this is somebody adding @Transactional back to the
        // method for tidiness, which would silently re-enclose the send.
        var method = assertDoesNotThrow(() -> OtpService.class.getMethod("sendOtp", String.class));
        assertNull(method.getAnnotation(org.springframework.transaction.annotation.Transactional.class),
                "sendOtp is @Transactional again, which puts the MSG91 call back inside the transaction.");
        verify(smsService, never()).sendOtp(null, null);
    }
}
