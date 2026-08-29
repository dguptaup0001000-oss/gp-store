package com.gpstore.otp;

import com.gpstore.auth.EmailIdentities;
import com.gpstore.auth.OtpPurpose;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Email OTP transport. Same {@link OtpProvider} shape as SMS. Never logs
 * OTP values or SMTP passwords.
 */
public class EmailOtpProvider implements OtpProvider {

    private static final Logger log = LoggerFactory.getLogger(EmailOtpProvider.class);

    private final JavaMailSender mailSender;
    private final String from;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, String> issued = new ConcurrentHashMap<>();

    public EmailOtpProvider(JavaMailSender mailSender, String from) {
        this.mailSender = mailSender;
        this.from = from == null ? "" : from.trim();
    }

    @Override
    public boolean issuesLocalCode() {
        return true;
    }

    @Override
    public SendResult send(String destination, OtpPurpose purpose, Duration expiry, String optionalOtp) {
        String code = optionalOtp != null && !optionalOtp.isBlank()
                ? optionalOtp
                : generateSixDigitCode();
        issued.put(key(destination, purpose), code);
        if (mailSender != null && !from.isBlank()) {
            try {
                MimeMessage message = mailSender.createMimeMessage();
                MimeMessageHelper helper = new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
                helper.setFrom(from);
                helper.setTo(destination);
                helper.setSubject("Your GP Store verification code");
                long minutes = Math.max(1, expiry == null ? 5 : expiry.toMinutes());
                helper.setText(
                        "Your GP Store verification code is " + code + ".\n"
                                + "It expires in " + minutes + " minutes.\n\n"
                                + "Do not share this code.",
                        false);
                mailSender.send(message);
            } catch (Exception ex) {
                issued.remove(key(destination, purpose));
                log.info("OTP_SEND_FAILURE dest={} purpose={} provider=email",
                        EmailIdentities.mask(destination), purpose);
                throw new OtpProviderException("Unable to send OTP right now. Please try again.");
            }
        }
        log.info("OTP_SEND_SUCCESS dest={} purpose={} provider=email",
                EmailIdentities.mask(destination), purpose);
        return SendResult.ok("email");
    }

    @Override
    public boolean verify(String destination, String otp, OtpPurpose purpose) {
        if (otp == null || otp.isBlank() || purpose == null) {
            return false;
        }
        return otp.equals(issued.get(key(destination, purpose)));
    }

    @Override
    public SendResult resend(String destination) {
        log.info("OTP_SEND_SUCCESS dest={} purpose=resend provider=email",
                EmailIdentities.mask(destination));
        return SendResult.ok("email-resend");
    }

    @Override
    public Optional<String> peekIssuedOtpForTests(String destination, OtpPurpose purpose) {
        return Optional.ofNullable(issued.get(key(destination, purpose)));
    }

    private String generateSixDigitCode() {
        return String.valueOf(100000 + random.nextInt(900000));
    }

    private static String key(String destination, OtpPurpose purpose) {
        return destination + ":" + purpose.name();
    }
}
