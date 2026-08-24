package com.gpstore.otp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gpstore.auth.OtpPurpose;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Msg91OtpProviderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @Test
    void successfulSendParsesRequestIdAndDoesNotPutAuthKeyInUrl() {
        RecordingExecutor http = new RecordingExecutor(200, "{\"type\":\"success\",\"request_id\":\"abc\"}");
        Msg91OtpProvider provider = provider(http);

        OtpProvider.SendResult result = provider.send("919876543210", OtpPurpose.LOGIN, Duration.ofMinutes(5), null);

        assertEquals("abc", result.providerReference());
        assertTrue(http.lastUri.toString().contains("/api/v5/otp"));
        assertFalse(http.lastUri.toString().contains("secret-auth-key"));
        assertEquals("secret-auth-key", http.lastHeaders.get("authkey"));
        assertTrue(http.lastUri.toString().contains("otp_expiry=5"));
        assertTrue(http.lastUri.toString().contains("otp_length=6"));
        assertFalse(http.lastUri.toString().contains("otp="));
    }

    @Test
    void httpErrorBecomesGenericFailure() {
        Msg91OtpProvider provider = provider(new RecordingExecutor(429, "{\"type\":\"error\",\"message\":\"insufficient balance\"}"));
        OtpProviderException ex = assertThrows(OtpProviderException.class,
                () -> provider.send("919876543210", OtpPurpose.LOGIN, Duration.ofMinutes(5), null));
        assertEquals(Msg91OtpProvider.GENERIC_SEND_FAILURE, ex.getMessage());
        assertFalse(ex.getMessage().toLowerCase().contains("balance"));
    }

    @Test
    void timeoutIsGeneric() {
        OtpHttpExecutor http = (method, uri, headers, body, timeout) -> {
            throw new OtpProviderException("MSG91 request timed out");
        };
        Msg91OtpProvider provider = provider(http);
        OtpProviderException ex = assertThrows(OtpProviderException.class,
                () -> provider.send("919876543210", OtpPurpose.LOGIN, Duration.ofMinutes(5), null));
        assertEquals(Msg91OtpProvider.GENERIC_SEND_FAILURE, ex.getMessage());
    }

    @Test
    void malformedJsonIsGeneric() {
        Msg91OtpProvider provider = provider(new RecordingExecutor(200, "<html>nope</html>"));
        assertThrows(OtpProviderException.class,
                () -> provider.send("919876543210", OtpPurpose.LOGIN, Duration.ofMinutes(5), null));
    }

    @Test
    void networkErrorIsGeneric() {
        OtpHttpExecutor http = (method, uri, headers, body, timeout) -> {
            throw new OtpProviderException("MSG91 network failure", new java.net.ConnectException("refused"));
        };
        Msg91OtpProvider provider = provider(http);
        assertEquals(Msg91OtpProvider.GENERIC_SEND_FAILURE,
                assertThrows(OtpProviderException.class,
                        () -> provider.send("919876543210", OtpPurpose.LOGIN, Duration.ofMinutes(5), null))
                        .getMessage());
    }

    @Test
    void verifySuccessAndFailure() {
        AtomicInteger calls = new AtomicInteger();
        OtpHttpExecutor http = (method, uri, headers, body, timeout) -> {
            int n = calls.incrementAndGet();
            assertEquals("GET", method);
            assertTrue(uri.toString().contains("/api/v5/otp/verify"));
            if (n == 1) {
                return new OtpHttpExecutor.Result(200, "{\"type\":\"success\",\"message\":\"OTP verified successfully\"}");
            }
            return new OtpHttpExecutor.Result(200, "{\"type\":\"error\",\"message\":\"OTP not match\"}");
        };
        Msg91OtpProvider provider = provider(http);
        assertTrue(provider.verify("919876543210", "654321", OtpPurpose.LOGIN));
        assertFalse(provider.verify("919876543210", "000000", OtpPurpose.LOGIN));
    }

    private static Msg91OtpProvider provider(OtpHttpExecutor http) {
        return new Msg91OtpProvider(
                "https://control.msg91.com",
                "secret-auth-key",
                "template-id",
                "GPSTOR",
                TIMEOUT,
                http,
                MAPPER);
    }

    private static final class RecordingExecutor implements OtpHttpExecutor {
        private final int status;
        private final String body;
        private URI lastUri;
        private Map<String, String> lastHeaders;
        private final List<URI> uris = new ArrayList<>();

        private RecordingExecutor(int status, String body) {
            this.status = status;
            this.body = body;
        }

        @Override
        public Result execute(String method, URI uri, Map<String, String> headers, String jsonBody, Duration timeout) {
            this.lastUri = uri;
            this.lastHeaders = headers;
            this.uris.add(uri);
            return new Result(status, body);
        }
    }
}
