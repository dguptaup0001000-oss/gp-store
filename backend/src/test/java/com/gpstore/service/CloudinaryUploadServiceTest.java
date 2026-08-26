package com.gpstore.service;

import com.gpstore.exception.ConflictException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CloudinaryUploadServiceTest {

    @Test
    @DisplayName("missing Cloudinary credentials fail closed")
    void unconfiguredSignatureIsRefused() {
        CloudinaryUploadService service = new CloudinaryUploadService("", "", "", "gp-store/products");
        ConflictException ex = assertThrows(ConflictException.class, service::generateSignedUploadParams);
        assertTrue(ex.getMessage().contains("CLOUDINARY"), ex.getMessage());
    }
}
