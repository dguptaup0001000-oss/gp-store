package com.gpstore.dto.response;

import com.gpstore.upload.CatalogImageRefs;

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

    /** Short-lived signed GET for display. Do not persist this string. */
    public String getPublicUrl() {
        return publicUrl;
    }

    /** Stable private-bucket reference to store in image_url. */
    public String getImageRef() {
        return CatalogImageRefs.storedRef(objectKey);
    }
}
