package com.gpstore.dto.request;

import com.gpstore.upload.ImageKind;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public class SignUploadRequest {

    @NotNull
    private ImageKind imageType;

    @NotBlank
    private String contentType;

    @Positive
    private long contentLength;

    /** Optional product-variant or category id when already known. */
    private Long ownerId;

    public ImageKind getImageType() {
        return imageType;
    }

    public void setImageType(ImageKind imageType) {
        this.imageType = imageType;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public long getContentLength() {
        return contentLength;
    }

    public void setContentLength(long contentLength) {
        this.contentLength = contentLength;
    }

    public Long getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(Long ownerId) {
        this.ownerId = ownerId;
    }
}
