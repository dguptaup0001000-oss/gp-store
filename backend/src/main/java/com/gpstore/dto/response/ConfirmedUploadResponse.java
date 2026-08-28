package com.gpstore.dto.response;

public class ConfirmedUploadResponse {

    private final String objectKey;
    private final String publicUrl;

    public ConfirmedUploadResponse(String objectKey, String publicUrl) {
        this.objectKey = objectKey;
        this.publicUrl = publicUrl;
    }

    public String getObjectKey() {
        return objectKey;
    }

    public String getPublicUrl() {
        return publicUrl;
    }
}
