package com.gpstore.otp;

import com.gpstore.auth.IndianPhoneNumbers;
import com.gpstore.auth.OtpPurpose;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory OTP provider for local development and automated tests.
 *
 * Never selected when {@code app.production=true}. Does not log OTP values
 * and does not accept a universal code such as 123456.
 */
public class MockOtpProvider implements OtpProvider {

    private static final Logger log = LoggerFactory.getLogger(MockOtpProvider.class);

    private final SecureRandom random = new SecureRandom();
    private final Map<String, String> issued = new ConcurrentHashMap<>();

    @Override
    public SendResult send(String mobileE164, OtpPurpose purpose, java.time.Duration expiry, String optionalOtp) {
        String code = optionalOtp != null && !optionalOtp.isBlank()
                ? optionalOtp
                : generateSixDigitCode();
        issued.put(key(mobileE164, purpose), code);
        log.info("OTP_SEND_SUCCESS phone={} purpose={} provider=mock",
                IndianPhoneNumbers.mask(mobileE164), purpose);
        return SendResult.ok("mock");
    }

    @Override
    public boolean verify(String mobileE164, String otp, OtpPurpose purpose) {
        if (otp == null || otp.isBlank() || purpose == null) {
            return false;
        }
        return otp.equals(issued.get(key(mobileE164, purpose)));
    }

    @Override
    public SendResult resend(String mobileE164) {
        log.info("OTP_SEND_SUCCESS phone={} purpose=resend provider=mock",
                IndianPhoneNumbers.mask(mobileE164));
        return SendResult.ok("mock-resend");
    }

    @Override
    public boolean issuesLocalCode() {
        return true;
    }

    @Override
    public Optional<String> peekIssuedOtpForTests(String mobileE164, OtpPurpose purpose) {
        return Optional.ofNullable(issued.get(key(mobileE164, purpose)));
    }

    public void clear() {
        issued.clear();
    }

    private String generateSixDigitCode() {
        return String.valueOf(100000 + random.nextInt(900000));
    }

    private static String key(String mobileE164, OtpPurpose purpose) {
        return mobileE164 + ":" + purpose.name();
    }
}
