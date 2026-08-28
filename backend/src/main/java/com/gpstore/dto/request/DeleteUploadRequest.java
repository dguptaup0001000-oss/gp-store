package com.gpstore.dto.request;

import jakarta.validation.constraints.NotBlank;

public class DeleteUploadRequest {

    @NotBlank
    private String publicUrl;

    public String getPublicUrl() {
        return publicUrl;
    }

    public void setPublicUrl(String publicUrl) {
        this.publicUrl = publicUrl;
    }
}
