package com.gpstore.service;

import com.gpstore.auth.IndianPhoneNumbers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.net.URLEncoder;
import java.time.Duration;

/**
 * Legacy MSG91 send helper. OTP login/reset now go through {@code OtpProvider}.
 * Kept so any leftover caller still cannot log an OTP value.
 */
@Service
public class SmsService {

    private static final Logger log = LoggerFactory.getLogger(SmsService.class);

    private final String authKey;
    private final String templateId;
    private final String senderId;
    private final boolean sendingEnabled;
    private final Duration requestTimeout;
    private final HttpClient httpClient;

    public SmsService(
            @Value("${otp.msg91-auth-key}") String authKey,
            @Value("${otp.msg91-template-id}") String templateId,
            @Value("${otp.msg91-sender-id}") String senderId,
            @Value("${otp.sms-sending-enabled}") boolean sendingEnabled,
            @Value("${otp.sms-connect-timeout-seconds:5}") int connectTimeoutSeconds,
            @Value("${otp.sms-request-timeout-seconds:5}") int requestTimeoutSeconds) {
        this.authKey = authKey;
        this.templateId = templateId;
        this.senderId = senderId;
        this.sendingEnabled = sendingEnabled;
        this.requestTimeout = Duration.ofSeconds(Math.max(1, requestTimeoutSeconds));
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(1, connectTimeoutSeconds)))
                .build();
    }

    public void sendOtp(String mobileNumber, String otpCode) {
        if (!sendingEnabled || authKey == null || authKey.isBlank()
                || templateId == null || templateId.isBlank()) {
            log.info("OTP_SEND_FAILURE phone={} provider=unconfigured",
                    IndianPhoneNumbers.mask(mobileNumber));
            return;
        }
        if (otpCode == null || otpCode.isBlank()) {
            log.info("OTP_SEND_FAILURE phone={} reason=missing_code", IndianPhoneNumbers.mask(mobileNumber));
            return;
        }

        try {
            String url = "https://control.msg91.com/api/v5/otp"
                    + "?template_id=" + URLEncoder.encode(templateId, StandardCharsets.UTF_8)
                    + "&mobile=" + URLEncoder.encode(IndianPhoneNumbers.normalizeTo91(mobileNumber), StandardCharsets.UTF_8)
                    + "&otp=" + URLEncoder.encode(otpCode, StandardCharsets.UTF_8)
                    + "&sender=" + URLEncoder.encode(senderId, StandardCharsets.UTF_8);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("authkey", authKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .timeout(requestTimeout)
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.error("MSG91 OTP send failed for {}: status={}",
                        IndianPhoneNumbers.mask(mobileNumber), response.statusCode());
            }
        } catch (Exception ex) {
            log.error("Failed to send OTP to {}", IndianPhoneNumbers.mask(mobileNumber), ex);
        }
    }
}
