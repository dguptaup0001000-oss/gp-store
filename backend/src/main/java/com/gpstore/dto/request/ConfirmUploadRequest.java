package com.gpstore.dto.request;

import jakarta.validation.constraints.NotBlank;

public class ConfirmUploadRequest {

    @NotBlank
    private String objectKey;

    public String getObjectKey() {
        return objectKey;
    }

    public void setObjectKey(String objectKey) {
        this.objectKey = objectKey;
    }
}
