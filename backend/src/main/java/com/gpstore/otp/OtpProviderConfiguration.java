package com.gpstore.otp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Duration;

@Configuration
public class OtpProviderConfiguration {

    @Bean
    @ConditionalOnMissingBean
    Clock clock() {
        return Clock.systemDefaultZone();
    }

    @Bean
    OtpProvider otpProvider(
            @Value("${app.production:false}") boolean production,
            @Value("${msg91.enabled:false}") boolean msg91Enabled,
            @Value("${msg91.base-url:https://control.msg91.com}") String baseUrl,
            @Value("${msg91.auth-key:}") String authKey,
            @Value("${msg91.otp-template-id:}") String templateId,
            @Value("${msg91.sender-id:GPSTOR}") String senderId,
            @Value("${otp.sms-connect-timeout-seconds:5}") int connectTimeoutSeconds,
            @Value("${otp.sms-request-timeout-seconds:5}") int requestTimeoutSeconds,
            ObjectMapper objectMapper) {
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

    /**
     * Visible for fail-closed unit tests. Production never receives the mock.
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
        if (production) {
            if (!msg91Enabled) {
                throw new IllegalStateException(
                        "Refusing to start in production with MSG91 disabled. "
                                + "Set MSG91_ENABLED=true (or OTP_SMS_SENDING_ENABLED=true) "
                                + "and configure MSG91_AUTH_KEY plus MSG91_OTP_TEMPLATE_ID / MSG91_TEMPLATE_ID. "
                                + "The mock OTP provider cannot run in production.");
            }
            requireCredentials(authKey, templateId);
            return new Msg91OtpProvider(
                    baseUrl, authKey, templateId, senderId, requestTimeout,
                    new JdkOtpHttpExecutor(connectTimeout), objectMapper);
        }
        if (msg91Enabled) {
            requireCredentials(authKey, templateId);
            return new Msg91OtpProvider(
                    baseUrl, authKey, templateId, senderId, requestTimeout,
                    new JdkOtpHttpExecutor(connectTimeout), objectMapper);
        }
        return new MockOtpProvider();
    }

    private static void requireCredentials(String authKey, String templateId) {
        if (authKey == null || authKey.isBlank() || templateId == null || templateId.isBlank()) {
            throw new IllegalStateException(
                    "MSG91 is enabled but MSG91_AUTH_KEY or the OTP template ID is missing. "
                            + "Set MSG91_AUTH_KEY and MSG91_OTP_TEMPLATE_ID (or MSG91_TEMPLATE_ID). "
                            + "Refusing to start rather than sending OTPs that can never be delivered.");
        }
    }
}
