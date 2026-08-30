package com.gpstore.otp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mail.javamail.JavaMailSender;

import java.time.Duration;

@Configuration
public class OtpProviderConfiguration {

    @Bean
    OtpProvider otpProvider(
            @Value("${app.production:false}") boolean production,
            @Value("${otp.channel:EMAIL}") String channel,
            @Value("${spring.mail.host:}") String smtpHost,
            @Value("${otp.email.from:}") String emailFrom,
            ObjectProvider<JavaMailSender> mailSender,
            ObjectProvider<StringRedisTemplate> redis,
            @Value("${msg91.enabled:false}") boolean msg91Enabled,
            @Value("${msg91.base-url:https://control.msg91.com}") String baseUrl,
            @Value("${msg91.auth-key:}") String authKey,
            @Value("${msg91.otp-template-id:}") String templateId,
            @Value("${msg91.sender-id:GPSTOR}") String senderId,
            @Value("${otp.sms-connect-timeout-seconds:5}") int connectTimeoutSeconds,
            @Value("${otp.sms-request-timeout-seconds:5}") int requestTimeoutSeconds,
            ObjectMapper objectMapper) {
        if (OtpChannel.from(channel) == OtpChannel.EMAIL) {
            return createEmail(production, smtpHost, emailFrom, mailSender.getIfAvailable(),
                    redis.getIfAvailable());
        }
        return create(
                production,
                msg91Enabled,
                baseUrl,
                authKey,
                templateId,
                senderId,
                Duration.ofSeconds(Math.max(1, connectTimeoutSeconds)),
                Duration.ofSeconds(Math.max(1, requestTimeoutSeconds)),
                objectMapper);
    }

    static OtpProvider createEmail(
            boolean production,
            String smtpHost,
            String from,
            JavaMailSender mailSender) {
        return createEmail(production, smtpHost, from, mailSender, null);
    }

    static OtpProvider createEmail(
            boolean production,
            String smtpHost,
            String from,
            JavaMailSender mailSender,
            StringRedisTemplate redis) {
        boolean smtpReady = present(smtpHost) && present(from) && mailSender != null;
        if (smtpReady) {
            return new EmailOtpProvider(mailSender, from, redis);
        }
        if (production) {
            return new UnconfiguredOtpProvider(
                    "Email OTP is unconfigured. Set SMTP_HOST and OTP_EMAIL_FROM on the VPS. "
                            + "Password login still works.");
        }
        return new EmailOtpProvider(null, from, redis);
    }

    /**
     * Visible for fail-closed unit tests. Production never receives the mock.
     * Missing MSG91 credentials do not block boot; SMS OTP stays fail-closed.
     */
    static OtpProvider create(
            boolean production,
            boolean msg91Enabled,
            String baseUrl,
            String authKey,
            String templateId,
            String senderId,
            Duration connectTimeout,
            Duration requestTimeout,
            ObjectMapper objectMapper) {
        boolean credentialsPresent = present(authKey) && present(templateId);
        if (msg91Enabled && credentialsPresent) {
            return new Msg91OtpProvider(
                    baseUrl, authKey, templateId, senderId, requestTimeout,
                    new JdkOtpHttpExecutor(connectTimeout), objectMapper);
        }
        if (production) {
            return new UnconfiguredOtpProvider();
        }
        if (msg91Enabled) {
            throw new IllegalStateException(
                    "MSG91 is enabled but MSG91_AUTH_KEY or the OTP template ID is missing. "
                            + "Set MSG91_AUTH_KEY and MSG91_OTP_TEMPLATE_ID (or MSG91_TEMPLATE_ID). "
                            + "Refusing to start rather than sending OTPs that can never be delivered.");
        }
        return new MockOtpProvider();
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }
}
