package com.gpstore.service;

import com.gpstore.exception.ConflictException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CloudinaryUploadServiceTest {

    @Test
    @DisplayName("the signature endpoint is off unless the flag is on")
    void signatureIsOffByDefault() {
        CloudinaryUploadService service = new CloudinaryUploadService(
                "cloud", "key", "secret", "gp-store/products");
        ConflictException ex = assertThrows(ConflictException.class, service::generateSignedUploadParams);
        assertTrue(ex.getMessage().contains("turned off"), ex.getMessage());
    }

    @Test
    @DisplayName("missing Cloudinary credentials fail closed when the flag is on")
    void unconfiguredSignatureIsRefused() {
        CloudinaryUploadService service = new CloudinaryUploadService(
                "", "", "", "gp-store/products", true);
        ConflictException ex = assertThrows(ConflictException.class, service::generateSignedUploadParams);
        assertTrue(ex.getMessage().contains("CLOUDINARY"), ex.getMessage());
    }

    @Test
    @DisplayName("enabled credentials still mint a signature")
    void enabledSignatureWorks() {
        CloudinaryUploadService service = new CloudinaryUploadService(
                "demo", "key", "secret", "gp-store/products", true);
        var signed = service.generateSignedUploadParams();
        assertTrue(signed.getSignature() != null && !signed.getSignature().isBlank());
        assertTrue(signed.getCloudName().equals("demo"));
    }
}
