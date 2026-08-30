package com.gpstore.otp;

import com.gpstore.auth.OtpPurpose;

import java.time.Duration;
import java.util.Optional;

/**
 * SMS OTP transport. Production uses MSG91 when credentials are set, otherwise
 * {@link UnconfiguredOtpProvider} (fail-closed, no mock). Local/CI uses
 * {@link MockOtpProvider}. Implementations must never log OTP values or auth keys.
 */
public interface OtpProvider {

    SendResult send(String mobileE164, OtpPurpose purpose, Duration expiry, String optionalOtp);

    boolean verify(String mobileE164, String otp, OtpPurpose purpose);

    SendResult resend(String mobileE164);

    /**
     * When true, {@code OtpService} generates and hashes the six-digit code
     * locally (email and mock). MSG91 generates the code itself.
     */
    default boolean issuesLocalCode() {
        return false;
    }

    /**
     * Test-only. Production providers return empty so a leaked test helper
     * cannot become a backdoor.
     */
    default Optional<String> peekIssuedOtpForTests(String mobileE164, OtpPurpose purpose) {
        return Optional.empty();
    }

    record SendResult(boolean sent, String providerReference) {
        public static SendResult ok(String providerReference) {
            return new SendResult(true, providerReference);
        }
    }
}
