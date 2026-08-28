package com.gpstore.upload;

import com.gpstore.exception.ConflictException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class R2ObjectStorageServiceTest {

    @AfterEach
    void resetHosts() {
        CatalogImageHosts.clearForTests();
        CatalogImageDelivery.unbindForTests();
    }

    @Test
    void blankCredentialsAreNotConfigured() {
        R2ObjectStorageService r2 = new R2ObjectStorageService("", "", "", "", "", "");
        assertFalse(r2.isConfigured());
        assertThrows(ConflictException.class, () -> r2.confirm("gpstore/products/1/original/a.jpg"));
    }

    @Test
    void privateBucketDoesNotRequireAPublicBaseUrl() {
        R2ObjectStorageService r2 = new R2ObjectStorageService(
                "acct", "", "key", "secret", "bucket", "");
        assertTrue(r2.isConfigured());
    }

    @Test
    void httpPublicBaseUrlIsIgnoredAndStillConfigured() {
        R2ObjectStorageService r2 = new R2ObjectStorageService(
                "acct", "", "key", "secret", "bucket", "http://pub.example.r2.dev");
        assertTrue(r2.isConfigured());
    }

    @Test
    void signedGetOutlivesDefaultCatalogCacheTtl() {
        assertTrue(R2ObjectStorageService.GET_TTL_SECONDS * 1000L > 600_000L,
                "Presigned GET must outlive CACHE_TTL_MS (default 10 minutes)");
    }

    @Test
    void connectionTestFailsClosedWhenR2IsUnset() {
        R2ObjectStorageService r2 = new R2ObjectStorageService("", "", "", "", "", "");
        assertThrows(ConflictException.class, r2::connectionTest);
    }

    @Test
    void storedRefsAndPublicBasePathsMapToOwnedKeys() {
        R2ObjectStorageService r2 = new R2ObjectStorageService(
                "acct", "", "key", "secret", "bucket", "https://cdn.example.r2.dev");
        assertEquals(
                "gpstore/products/9/original/a.webp",
                r2.keyFromOptionalPublicBase("https://cdn.example.r2.dev/gpstore/products/9/original/a.webp"));
        assertNull(r2.keyFromOptionalPublicBase("https://cdn.example.r2.dev/etc/passwd"));
        assertEquals(
                "gpstore/products/9/original/a.webp",
                CatalogImageRefs.objectKeyFrom("r2:gpstore/products/9/original/a.webp"));
        assertNull(CatalogImageRefs.objectKeyFrom("https://res.cloudinary.com/demo/image/upload/v1/x.jpg"));
        assertNull(CatalogImageRefs.objectKeyFrom("r2:gpstore/products/../secret"));
    }
}
