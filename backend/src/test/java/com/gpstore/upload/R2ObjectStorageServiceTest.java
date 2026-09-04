package com.gpstore.upload;

import com.gpstore.dto.request.SignUploadRequest;
import com.gpstore.exception.BadRequestException;
import com.gpstore.exception.ConflictException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.MetadataDirective;

import java.util.ArrayList;
import java.util.List;

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
    void workerBaseUrlIsStableAndDoesNotNeedPresign() {
        R2ObjectStorageService r2 = new R2ObjectStorageService(
                "acct", "", "key", "secret", "bucket", "", "https://img.gpstore.co.in");
        String first = r2.deliveryUrl("gpstore/products/1/original/a.jpg");
        String second = r2.deliveryUrl("gpstore/products/1/original/a.jpg");
        assertEquals("https://img.gpstore.co.in/gpstore/products/1/original/a.jpg", first);
        assertEquals(first, second);
        assertFalse(first.contains("X-Amz-"), first);
    }

    @Test
    void workerUrlIsNotUsedForStagingKeys() {
        R2ObjectStorageService r2 = new R2ObjectStorageService(
                "acct", "", "key", "secret", "bucket", "", "https://img.gpstore.co.in");
        assertThrows(ConflictException.class,
                () -> r2.deliveryUrl("gpstore/staging/products/1/original/a.jpg"),
                "staging must not be served from the Worker URL");
    }

    @Test
    void httpWorkerBaseUrlIsIgnored() {
        R2ObjectStorageService r2 = new R2ObjectStorageService(
                "acct", "", "key", "secret", "bucket", "", "http://img.gpstore.co.in");
        assertThrows(ConflictException.class,
                () -> r2.deliveryUrl("gpstore/products/1/original/a.jpg"));
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

    @Test
    void batchSignRejectsMoreThanTwenty() {
        R2ObjectStorageService r2 = new R2ObjectStorageService("", "", "", "", "", "");
        List<SignUploadRequest> items = new ArrayList<>();
        for (int i = 0; i < UploadPolicy.BATCH_MAX + 1; i++) {
            SignUploadRequest request = new SignUploadRequest();
            request.setImageType(ImageKind.PRODUCT);
            request.setContentType("image/jpeg");
            request.setContentLength(1024);
            items.add(request);
        }
        BadRequestException ex = assertThrows(BadRequestException.class, () -> r2.signBatch(items));
        assertTrue(ex.getMessage().contains("at most " + UploadPolicy.BATCH_MAX), ex.getMessage());
    }

    @Test
    void batchConfirmRejectsEmpty() {
        R2ObjectStorageService r2 = new R2ObjectStorageService("", "", "", "", "", "");
        assertThrows(BadRequestException.class, () -> r2.confirmBatch(List.of()));
    }

    /**
     * A MISSING body, not an empty one, and the two arrive differently: an
     * absent JSON array deserialises to null, so `null` is what these methods
     * actually see when a client sends nothing at all.
     *
     * Both methods already handled it - `requireBatchSize(x == null ? 0 : ...)`
     * throws on 0 before the dereference below can happen - but nothing pinned
     * that, and the shape was fragile: the null-safety lived in a separate
     * method, so the guarantee was one careless edit away from a
     * NullPointerException reaching the controller as a 500 instead of the 400
     * the caller deserves. SpotBugs flagged exactly that as
     * NP_NULL_ON_SOME_PATH, and it was right to.
     *
     * These two cases are what let the code be rewritten to normalise first
     * and check second without taking the behaviour on trust.
     */
    @Test
    void batchSignRejectsNullRatherThanThrowingNullPointer() {
        R2ObjectStorageService r2 = new R2ObjectStorageService("", "", "", "", "", "");
        assertThrows(BadRequestException.class, () -> r2.signBatch(null));
    }

    @Test
    void batchConfirmRejectsNullRatherThanThrowingNullPointer() {
        R2ObjectStorageService r2 = new R2ObjectStorageService("", "", "", "", "", "");
        assertThrows(BadRequestException.class, () -> r2.confirmBatch(null));
    }

    @Test
    void batchOfTwentyPassesTheSizeGateThenFailsClosedWithoutR2() {
        R2ObjectStorageService r2 = new R2ObjectStorageService("", "", "", "", "", "");
        List<SignUploadRequest> items = new ArrayList<>();
        for (int i = 0; i < UploadPolicy.BATCH_MAX; i++) {
            SignUploadRequest request = new SignUploadRequest();
            request.setImageType(ImageKind.PRODUCT);
            request.setContentType("image/jpeg");
            request.setContentLength(1024);
            items.add(request);
        }
        assertThrows(ConflictException.class, () -> r2.signBatch(items));
    }

    @Test
    void promoteCopySetsImmutableCacheControlOnPermanentObject() {
        CopyObjectRequest copy = R2ObjectStorageService.promoteCopy(
                "gp-store",
                "gpstore/staging/products/new/original/a.jpg",
                "gpstore/products/new/original/a.jpg",
                "image/jpeg");

        assertEquals(MetadataDirective.REPLACE, copy.metadataDirective());
        assertEquals(R2ObjectStorageService.CACHE_CONTROL, copy.cacheControl());
        assertEquals("image/jpeg", copy.contentType());
        assertEquals("gpstore/staging/products/new/original/a.jpg", copy.sourceKey());
        assertEquals("gpstore/products/new/original/a.jpg", copy.destinationKey());
    }

    @Test
    void connectionTestMessageNamesTheFailedPath() {
        assertEquals(
                "R2 connection test passed (direct PUT and presigned PUT).",
                R2ObjectStorageService.connectionTestMessage(true, true, true, true));
        assertEquals(
                "R2 direct PUT succeeded but the presigned PUT failed. Admin uploads use the presigned path.",
                R2ObjectStorageService.connectionTestMessage(true, false, false, false));
        assertEquals(
                "R2 presigned PUT succeeded but the direct PUT failed.",
                R2ObjectStorageService.connectionTestMessage(false, true, false, false));
        assertEquals(
                "R2 uploads succeeded but HEAD verification failed.",
                R2ObjectStorageService.connectionTestMessage(true, true, false, false));
        assertEquals(
                "R2 uploads succeeded but the probe object could not be deleted.",
                R2ObjectStorageService.connectionTestMessage(true, true, true, false));
        assertEquals(
                "R2 connection test failed. Check bucket name, endpoint, and API token on the VPS.",
                R2ObjectStorageService.connectionTestMessage(false, false, false, false));
    }
}
