package com.gpstore.service;

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

@Service
public class SmsService {

    private static final Logger log = LoggerFactory.getLogger(SmsService.class);

    private final String authKey;
    private final String templateId;
    private final String senderId;
    private final boolean sendingEnabled;
    private final HttpClient httpClient;

    public SmsService(
            @Value("${otp.msg91-auth-key}") String authKey,
            @Value("${otp.msg91-template-id}") String templateId,
            @Value("${otp.msg91-sender-id}") String senderId,
            @Value("${otp.sms-sending-enabled}") boolean sendingEnabled) {
        this.authKey = authKey;
        this.templateId = templateId;
        this.senderId = senderId;
        this.sendingEnabled = sendingEnabled;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    /**
     * Sends via MSG91's OTP API. When otp.sms-sending-enabled=false (the dev
     * default), this only logs the code instead of actually sending/spending
     * money - lets you test the full login flow before MSG91/DLT are set up.
     * Real production use REQUIRES setting OTP_SMS_SENDING_ENABLED=true plus
     * your real MSG91 auth key and DLT-registered template ID.
     */
    public void sendOtp(String mobileNumber, String otpCode) {
        if (!sendingEnabled) {
            log.info("[DEV MODE - no SMS sent] OTP for {}: {}", mobileNumber, otpCode);
            return;
        }

        try {
            String url = "https://control.msg91.com/api/v5/otp"
                    + "?template_id=" + URLEncoder.encode(templateId, StandardCharsets.UTF_8)
                    + "&mobile=" + URLEncoder.encode(mobileNumber, StandardCharsets.UTF_8)
                    + "&otp=" + URLEncoder.encode(otpCode, StandardCharsets.UTF_8)
                    + "&sender=" + URLEncoder.encode(senderId, StandardCharsets.UTF_8);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("authkey", authKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .timeout(Duration.ofSeconds(10))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.error("MSG91 OTP send failed for {}: status={}, body={}",
                        mobileNumber, response.statusCode(), response.body());
            }
        } catch (Exception ex) {
            // Deliberately doesn't throw - a transient SMS provider outage
            // shouldn't turn into a 500 the customer can't recover from; they
            // can just tap "resend" (which is rate-limited separately, see
            // OtpService). The failure IS logged so it's not silently lost.
            log.error("Failed to send OTP to {}", mobileNumber, ex);
        }
    }
}
