package com.gpstore.otp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gpstore.auth.IndianPhoneNumbers;
import com.gpstore.auth.OtpPurpose;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;

/**
 * Official MSG91 OTP v5 API.
 *
 * Send: {@code POST {base}/api/v5/otp}
 * Verify: {@code GET {base}/api/v5/otp/verify}
 * Resend: {@code GET {base}/api/v5/otp/retry}
 *
 * Auth key is sent only as the {@code authkey} header — never in query strings
 * that might end up in access logs, and never returned to clients.
 */
public class Msg91OtpProvider implements OtpProvider {

    private static final Logger log = LoggerFactory.getLogger(Msg91OtpProvider.class);

    static final String GENERIC_SEND_FAILURE = "Unable to send OTP right now. Please try again.";

    private final String baseUrl;
    private final String authKey;
    private final String templateId;
    private final String senderId;
    private final Duration requestTimeout;
    private final OtpHttpExecutor http;
    private final ObjectMapper objectMapper;

    public Msg91OtpProvider(
            String baseUrl,
            String authKey,
            String templateId,
            String senderId,
            Duration requestTimeout,
            OtpHttpExecutor http,
            ObjectMapper objectMapper) {
        this.baseUrl = trimTrailingSlash(baseUrl);
        this.authKey = authKey;
        this.templateId = templateId;
        this.senderId = senderId;
        this.requestTimeout = requestTimeout;
        this.http = http;
        this.objectMapper = objectMapper;
    }

    @Override
    public SendResult send(String mobileE164, OtpPurpose purpose, Duration expiry, String optionalOtp) {
        long expiryMinutes = Math.min(5, Math.max(1, expiry.toMinutes()));
        StringBuilder path = new StringBuilder(baseUrl)
                .append("/api/v5/otp")
                .append("?template_id=").append(enc(templateId))
                .append("&mobile=").append(enc(mobileE164))
                .append("&otp_expiry=").append(expiryMinutes)
                .append("&otp_length=6");
        if (senderId != null && !senderId.isBlank()) {
            path.append("&sender=").append(enc(senderId));
        }
        if (optionalOtp != null && !optionalOtp.isBlank()) {
            path.append("&otp=").append(enc(optionalOtp));
        }
        return postOtp(path.toString(), mobileE164, "OTP_SEND");
    }

    @Override
    public boolean verify(String mobileE164, String otp, OtpPurpose purpose) {
        if (otp == null || !otp.matches("^\\d{6}$")) {
            return false;
        }
        String url = baseUrl + "/api/v5/otp/verify"
                + "?mobile=" + enc(mobileE164)
                + "&otp=" + enc(otp);
        try {
            OtpHttpExecutor.Result result = http.execute(
                    "GET", URI.create(url), authHeaders(), null, requestTimeout);
            Msg91Payload payload = parse(result.body());
            if (result.statusCode() >= 200 && result.statusCode() < 300 && payload.success()) {
                log.info("OTP_VERIFY_SUCCESS phone={} provider=msg91", IndianPhoneNumbers.mask(mobileE164));
                return true;
            }
            log.info("OTP_VERIFY_FAILURE phone={} provider=msg91 status={}",
                    IndianPhoneNumbers.mask(mobileE164), result.statusCode());
            return false;
        } catch (OtpProviderException ex) {
            log.info("OTP_VERIFY_FAILURE phone={} provider=msg91 cause=transport",
                    IndianPhoneNumbers.mask(mobileE164));
            throw ex;
        }
    }

    @Override
    public SendResult resend(String mobileE164) {
        String url = baseUrl + "/api/v5/otp/retry"
                + "?retrytype=text&mobile=" + enc(mobileE164);
        return postOtp(url, mobileE164, "OTP_RESEND");
    }

    private SendResult postOtp(String url, String mobileE164, String eventPrefix) {
        try {
            OtpHttpExecutor.Result result = http.execute(
                    "POST",
                    URI.create(url),
                    authHeaders(),
                    "{}",
                    requestTimeout);
            Msg91Payload payload = parse(result.body());
            if (result.statusCode() >= 200 && result.statusCode() < 300 && payload.success()) {
                log.info("{}_SUCCESS phone={} provider=msg91", eventPrefix, IndianPhoneNumbers.mask(mobileE164));
                return SendResult.ok(payload.requestId());
            }
            log.info("{}_FAILURE phone={} provider=msg91 status={}",
                    eventPrefix, IndianPhoneNumbers.mask(mobileE164), result.statusCode());
            throw new OtpProviderException(GENERIC_SEND_FAILURE);
        } catch (OtpProviderException ex) {
            if (GENERIC_SEND_FAILURE.equals(ex.getMessage())) {
                throw ex;
            }
            log.info("{}_FAILURE phone={} provider=msg91 cause=transport",
                    eventPrefix, IndianPhoneNumbers.mask(mobileE164));
            throw new OtpProviderException(GENERIC_SEND_FAILURE, ex);
        }
    }

    private Map<String, String> authHeaders() {
        return Map.of(
                "authkey", authKey,
                "accept", "application/json",
                "Content-Type", "application/json");
    }

    private Msg91Payload parse(String body) {
        if (body == null || body.isBlank()) {
            throw new OtpProviderException(GENERIC_SEND_FAILURE);
        }
        try {
            JsonNode node = objectMapper.readTree(body);
            String type = text(node, "type");
            String message = text(node, "message");
            String requestId = text(node, "request_id");
            if (requestId.isBlank()) {
                requestId = text(node, "requestId");
            }
            boolean success = "success".equalsIgnoreCase(type)
                    || (type.isBlank() && message.toLowerCase(Locale.ROOT).contains("success"));
            return new Msg91Payload(success, requestId);
        } catch (OtpProviderException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new OtpProviderException(GENERIC_SEND_FAILURE, ex);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "" : value.asText("");
    }

    private static String enc(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static String trimTrailingSlash(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return "https://control.msg91.com";
        }
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    private record Msg91Payload(boolean success, String requestId) {
    }
}
