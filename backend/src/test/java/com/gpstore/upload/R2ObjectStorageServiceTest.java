package com.gpstore.upload;

import com.gpstore.exception.ConflictException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class R2ObjectStorageServiceTest {

    @AfterEach
    void resetHosts() {
        CatalogImageHosts.clearForTests();
    }

    @Test
    void blankCredentialsAreNotConfigured() {
        R2ObjectStorageService r2 = new R2ObjectStorageService("", "", "", "", "");
        assertFalse(r2.isConfigured());
        assertThrows(ConflictException.class, () -> r2.confirm("gpstore/products/1/original/a.jpg"));
    }

    @Test
    void publicUrlMustBeHttps() {
        R2ObjectStorageService r2 = new R2ObjectStorageService(
                "acct", "key", "secret", "bucket", "http://pub.example.r2.dev");
        assertFalse(r2.isConfigured());
    }

    @Test
    void onlyGpstoreKeysAreExtractedFromThePublicBase() {
        R2ObjectStorageService r2 = new R2ObjectStorageService(
                "acct", "key", "secret", "bucket", "https://cdn.example.r2.dev");
        assertEquals(
                "gpstore/products/9/original/a.webp",
                r2.keyFromPublicUrl("https://cdn.example.r2.dev/gpstore/products/9/original/a.webp"));
        assertNull(r2.keyFromPublicUrl("https://cdn.example.r2.dev/etc/passwd"));
        assertNull(r2.keyFromPublicUrl("https://res.cloudinary.com/demo/image/upload/v1/x.jpg"));
        assertNull(r2.keyFromPublicUrl("https://cdn.example.r2.dev/gpstore/products/../secret"));
    }
}
