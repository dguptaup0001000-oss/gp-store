package com.gpstore.catalog;

import com.gpstore.exception.BadRequestException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CatalogUrlValidatorTest {

    @Test
    void cloudinaryHttpsIsAllowedForImages() {
        assertTrue(CatalogUrlValidator.isAllowedImageUrl(
                "https://res.cloudinary.com/demo/image/upload/v1/gp/bag.jpg"));
    }

    @Test
    void lookalikeCloudinaryHostIsRejected() {
        assertFalse(CatalogUrlValidator.isAllowedImageUrl(
                "https://res.cloudinary.com.evil.example/x.jpg"));
        assertFalse(CatalogUrlValidator.isAllowedImageUrl(
                "https://evil.example/payload.jpg"));
        assertFalse(CatalogUrlValidator.isAllowedImageUrl("javascript:alert(1)"));
        assertFalse(CatalogUrlValidator.isAllowedImageUrl(
                "https://user:pass@res.cloudinary.com/demo/x.jpg"));
        assertFalse(CatalogUrlValidator.isAllowedImageUrl("http://res.cloudinary.com/demo/x.jpg"));
    }

    @Test
    void blankImageUrlIsAllowed() {
        assertDoesNotThrow(() -> CatalogUrlValidator.requireAllowedImageUrlOrEmpty(null));
        assertDoesNotThrow(() -> CatalogUrlValidator.requireAllowedImageUrlOrEmpty(""));
        assertDoesNotThrow(() -> CatalogUrlValidator.requireAllowedImageUrlOrEmpty("  "));
    }

    @Test
    void evilImageUrlThrows() {
        assertThrows(BadRequestException.class,
                () -> CatalogUrlValidator.requireAllowedImageUrlOrEmpty("https://evil.example/x.jpg"));
    }

    @Test
    void model3dRejectsPrivateAndNonHttps() {
        assertThrows(BadRequestException.class,
                () -> CatalogUrlValidator.requireAllowedModel3dUrl("javascript:alert(1)"));
        assertThrows(BadRequestException.class,
                () -> CatalogUrlValidator.requireAllowedModel3dUrl("http://cdn.example.com/atta.glb"));
        assertThrows(BadRequestException.class,
                () -> CatalogUrlValidator.requireAllowedModel3dUrl("https://127.0.0.1/atta.glb"));
        assertThrows(BadRequestException.class,
                () -> CatalogUrlValidator.requireAllowedModel3dUrl("https://10.0.0.5/atta.glb"));
        assertDoesNotThrow(() -> CatalogUrlValidator.requireAllowedModel3dUrl(
                "https://res.cloudinary.com/demo/raw/upload/atta.glb"));
        assertDoesNotThrow(() -> CatalogUrlValidator.requireAllowedModel3dUrl(
                "https://cdn.example.com/atta.glb"));
        assertDoesNotThrow(() -> CatalogUrlValidator.requireAllowedModel3dUrl(null));
    }
}
