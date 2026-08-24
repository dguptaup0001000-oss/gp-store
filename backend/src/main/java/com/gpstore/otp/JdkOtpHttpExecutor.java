package com.gpstore.otp;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Map;

/**
 * JDK {@link HttpClient} adapter used by {@link Msg91OtpProvider} in production.
 */
public class JdkOtpHttpExecutor implements OtpHttpExecutor {

    private final HttpClient httpClient;

    public JdkOtpHttpExecutor(Duration connectTimeout) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .build();
    }

    @Override
    public Result execute(String method, URI uri, Map<String, String> headers, String jsonBody, Duration timeout) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(uri)
                    .timeout(timeout);
            headers.forEach(builder::header);
            if ("GET".equalsIgnoreCase(method)) {
                builder.GET();
            } else {
                String body = jsonBody == null ? "" : jsonBody;
                builder.method(method.toUpperCase(), HttpRequest.BodyPublishers.ofString(body));
            }
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            return new Result(response.statusCode(), response.body());
        } catch (HttpTimeoutException ex) {
            throw new OtpProviderException("MSG91 request timed out", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new OtpProviderException("MSG91 request interrupted", ex);
        } catch (OtpProviderException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new OtpProviderException("MSG91 network failure", ex);
        }
    }
}
