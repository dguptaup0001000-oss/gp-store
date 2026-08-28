package com.gpstore.dto.response;

/**
 * Admin-only probe: put a tiny object, head it, delete it. Credentials are
 * never included in this payload.
 */
public class R2ConnectionTestResponse {

    private final boolean configured;
    private final boolean uploaded;
    private final boolean verified;
    private final boolean deleted;
    private final boolean ok;
    private final String message;

    public R2ConnectionTestResponse(
            boolean configured,
            boolean uploaded,
            boolean verified,
            boolean deleted,
            boolean ok,
            String message) {
        this.configured = configured;
        this.uploaded = uploaded;
        this.verified = verified;
        this.deleted = deleted;
        this.ok = ok;
        this.message = message;
    }

    public boolean isConfigured() {
        return configured;
    }

    public boolean isUploaded() {
        return uploaded;
    }

    public boolean isVerified() {
        return verified;
    }

    public boolean isDeleted() {
        return deleted;
    }

    public boolean isOk() {
        return ok;
    }

    public String getMessage() {
        return message;
    }
}
