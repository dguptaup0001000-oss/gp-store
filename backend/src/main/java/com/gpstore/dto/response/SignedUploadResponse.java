package com.gpstore.dto.response;

import java.util.Map;

/**
 * Short-lived R2 PUT authorization. The secret access key never leaves the
 * Hostinger backend. Flutter uploads bytes to {@code uploadUrl} only.
 */
public class SignedUploadResponse {

    private final String uploadUrl;
    private final String objectKey;
    private final String publicUrl;
    private final String method;
    private final Map<String, String> headers;
    private final long expiresAtEpochSeconds;

    public SignedUploadResponse(
            String uploadUrl,
            String objectKey,
            String publicUrl,
            String method,
            Map<String, String> headers,
            long expiresAtEpochSeconds) {
        this.uploadUrl = uploadUrl;
        this.objectKey = objectKey;
        this.publicUrl = publicUrl;
        this.method = method;
        this.headers = headers;
        this.expiresAtEpochSeconds = expiresAtEpochSeconds;
    }

    public String getUploadUrl() {
        return uploadUrl;
    }

    public String getObjectKey() {
        return objectKey;
    }

    public String getPublicUrl() {
        return publicUrl;
    }

    public String getMethod() {
        return method;
    }

    /**
     * Headers the client must send on the presigned PUT. This map is the
     * only contract: any header signed into the URL that is missing here
     * is a 403. Do not add extra headers the client was not told to send.
     */
    public Map<String, String> getHeaders() {
        return headers;
    }

    public long getExpiresAtEpochSeconds() {
        return expiresAtEpochSeconds;
    }
}
