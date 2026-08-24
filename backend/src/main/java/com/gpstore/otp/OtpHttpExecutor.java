package com.gpstore.otp;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

/**
 * Narrow HTTP port so MSG91 send/verify/resend can be tested without a live network.
 */
@FunctionalInterface
public interface OtpHttpExecutor {

    Result execute(String method, URI uri, Map<String, String> headers, String jsonBody, Duration timeout);

    record Result(int statusCode, String body) {
    }
}
