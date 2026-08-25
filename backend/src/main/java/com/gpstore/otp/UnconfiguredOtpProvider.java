package com.gpstore.otp;

import com.gpstore.auth.IndianPhoneNumbers;
import com.gpstore.auth.OtpPurpose;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

/**
 * Production stand-in when no SMS OTP provider is configured.
 *
 * The process is allowed to start (catalog, password login, health). Send and
 * resend fail closed so a customer is never told an OTP was delivered. Never
 * logs OTP values or provider secrets. Never selected outside production;
 * local/CI keeps {@link MockOtpProvider}.
 */
public class UnconfiguredOtpProvider implements OtpProvider {

    private static final Logger log = LoggerFactory.getLogger(UnconfiguredOtpProvider.class);

    static final String GENERIC_SEND_FAILURE = "Unable to send OTP right now. Please try again.";

    public UnconfiguredOtpProvider() {
        log.warn("OTP SMS provider is unconfigured. Password login still works. "
                + "LOGIN and password-reset OTP will not be delivered until a real "
                + "provider (MSG91_AUTH_KEY and MSG91_OTP_TEMPLATE_ID) is set. "
                + "The mock provider is not used in production.");
    }

    @Override
    public SendResult send(String mobileE164, OtpPurpose purpose, Duration expiry, String optionalOtp) {
        log.info("OTP_SEND_FAILURE phone={} purpose={} provider=unconfigured",
                IndianPhoneNumbers.mask(mobileE164), purpose);
        throw new OtpProviderException(GENERIC_SEND_FAILURE);
    }

    @Override
    public boolean verify(String mobileE164, String otp, OtpPurpose purpose) {
        log.info("OTP_VERIFY_FAILURE phone={} purpose={} provider=unconfigured",
                IndianPhoneNumbers.mask(mobileE164), purpose);
        return false;
    }

    @Override
    public SendResult resend(String mobileE164) {
        log.info("OTP_SEND_FAILURE phone={} purpose=resend provider=unconfigured",
                IndianPhoneNumbers.mask(mobileE164));
        throw new OtpProviderException(GENERIC_SEND_FAILURE);
    }
}
