package com.gpstore.upload;

import com.gpstore.exception.BadRequestException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class UploadPolicyTest {

    @Test
    void jpegPngWebpAreAllowed() {
        assertDoesNotThrow(() -> UploadPolicy.requireAllowedUpload(
                ImageKind.PRODUCT, "image/jpeg", 1024));
        assertDoesNotThrow(() -> UploadPolicy.requireAllowedUpload(
                ImageKind.PRODUCT, "image/png; charset=binary", 1024));
        assertDoesNotThrow(() -> UploadPolicy.requireAllowedUpload(
                ImageKind.CATEGORY, "image/webp", 2048));
    }

    @Test
    void svgHtmlZipOctetStreamAndExeAreRejected() {
        assertThrows(BadRequestException.class, () -> UploadPolicy.requireAllowedUpload(
                ImageKind.PRODUCT, "image/svg+xml", 100));
        assertThrows(BadRequestException.class, () -> UploadPolicy.requireAllowedUpload(
                ImageKind.PRODUCT, "text/html", 100));
        assertThrows(BadRequestException.class, () -> UploadPolicy.requireAllowedUpload(
                ImageKind.PRODUCT, "application/zip", 100));
        assertThrows(BadRequestException.class, () -> UploadPolicy.requireAllowedUpload(
                ImageKind.PRODUCT, "application/octet-stream", 100));
        assertThrows(BadRequestException.class, () -> UploadPolicy.requireAllowedUpload(
                ImageKind.PRODUCT, "application/javascript", 100));
    }

    @Test
    void oversizedProductImageIsRejected() {
        BadRequestException ex = assertThrows(BadRequestException.class,
                () -> UploadPolicy.requireAllowedUpload(
                        ImageKind.PRODUCT, "image/jpeg", UploadPolicy.DEFAULT_MAX_BYTES + 1L));
        assertTrue(ex.getMessage().toLowerCase().contains("too large"), ex.getMessage());
    }

    @Test
    void objectKeyIgnoresClientFilenamesAndUsesSafeIds() {
        String key = UploadPolicy.objectKey(ImageKind.PRODUCT, 42L, "image/jpeg");
        assertTrue(key.startsWith("gpstore/products/42/original/"), key);
        assertTrue(key.endsWith(".jpg"), key);
        assertFalse(key.contains(".."), key);
        assertFalse(key.contains(" "), key);
        assertFalse(key.toLowerCase().contains("passwd"));
    }

    @Test
    void unknownOwnerUsesNewFolder() {
        String key = UploadPolicy.objectKey(ImageKind.CATEGORY, null, "image/png");
        assertTrue(key.startsWith("gpstore/categories/new/original/"), key);
        assertTrue(key.endsWith(".png"), key);
    }

    @Test
    void magicBytesDetectJpegPngWebpAndRejectHtml() {
        byte[] jpeg = new byte[12];
        jpeg[0] = (byte) 0xFF;
        jpeg[1] = (byte) 0xD8;
        jpeg[2] = (byte) 0xFF;
        assertEquals("image/jpeg", UploadPolicy.detectContentType(jpeg));

        byte[] png = new byte[12];
        png[0] = (byte) 0x89;
        png[1] = 0x50;
        png[2] = 0x4E;
        png[3] = 0x47;
        assertEquals("image/png", UploadPolicy.detectContentType(png));

        byte[] webp = new byte[12];
        webp[0] = 'R';
        webp[1] = 'I';
        webp[2] = 'F';
        webp[3] = 'F';
        webp[8] = 'W';
        webp[9] = 'E';
        webp[10] = 'B';
        webp[11] = 'P';
        assertEquals("image/webp", UploadPolicy.detectContentType(webp));

        assertNull(UploadPolicy.detectContentType("<html>xxxxx".getBytes()));
        assertNull(UploadPolicy.detectContentType(new byte[] {0x00}));
    }
}
