package com.gpstore.upload;

import com.gpstore.dto.request.SignUploadRequest;
import com.gpstore.dto.response.SignedUploadResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class R2PresignedUploadTest {

    private S3Presigner presigner;

    @AfterEach
    void closePresigner() {
        if (presigner != null) {
            presigner.close();
        }
        CatalogImageHosts.clearForTests();
        CatalogImageDelivery.unbindForTests();
    }

    @Test
    @DisplayName("every signed header is advertised or transport-managed")
    void advertisedHeadersCoverEverySignedHeader() throws Exception {
        try (PresignedPutStub stub = new PresignedPutStub()) {
            R2ObjectStorageService r2 = service(stub.endpoint());
            SignedUploadResponse signed = r2.sign(jpegSignRequest(512));

            String signedHeaderList = PresignedPutStub.query(URI.create(signed.getUploadUrl()))
                    .get("x-amz-signedheaders");
            assertTrue(signedHeaderList != null && !signedHeaderList.isBlank(),
                    "presigned URL must list X-Amz-SignedHeaders");
            assertFalse(signedHeaderList.toLowerCase(Locale.ROOT).contains("cache-control"),
                    "Cache-Control must not be signed on the presigned PUT");

            Map<String, String> advertised = signed.getHeaders();
            for (String name : signedHeaderList.split(";")) {
                String header = name.trim();
                boolean transportManaged = R2ObjectStorageService.TRANSPORT_MANAGED_HEADERS
                        .contains(header.toLowerCase(Locale.ROOT));
                boolean inAdvertised = containsHeader(advertised, header);
                assertTrue(inAdvertised || transportManaged,
                        header + " is signed but neither advertised nor transport-managed");
                if (transportManaged) {
                    assertFalse(inAdvertised,
                            header + " is transport-managed and must not be advertised");
                }
            }
            assertTrue(containsHeader(advertised, "content-type"),
                    "content-type must be advertised");
        }
    }

    @Test
    @DisplayName("a real HTTP PUT using only advertised headers is 200")
    void presignedPutWithOnlyAdvertisedHeadersSucceeds() throws Exception {
        try (PresignedPutStub stub = new PresignedPutStub()) {
            R2ObjectStorageService r2 = service(stub.endpoint());
            byte[] bytes = new byte[512];
            SignedUploadResponse signed = r2.sign(jpegSignRequest(bytes.length));

            int status = R2ObjectStorageService.putUsingAdvertisedHeaders(signed, bytes);
            assertEquals(200, status,
                    "PUT with only advertised headers must succeed (presigned URL not logged)");
        }
    }

    @Test
    @DisplayName("omitting a signed advertised header is a 403, like production R2")
    void omittingSignedAdvertisedHeaderIsForbidden() throws Exception {
        try (PresignedPutStub stub = new PresignedPutStub()) {
            R2ObjectStorageService r2 = service(stub.endpoint());
            SignedUploadResponse signed = r2.sign(jpegSignRequest(32));
            SignedUploadResponse withoutType = new SignedUploadResponse(
                    signed.getUploadUrl(),
                    signed.getObjectKey(),
                    signed.getPublicUrl(),
                    signed.getMethod(),
                    Map.of(),
                    signed.getExpiresAtEpochSeconds());

            int status = R2ObjectStorageService.putUsingAdvertisedHeaders(withoutType, new byte[32]);
            assertEquals(403, status,
                    "a PUT that omits signed headers must 403 (presigned URL not logged)");
        }
    }

    private R2ObjectStorageService service(URI endpoint) {
        presigner = S3Presigner.builder()
                .endpointOverride(endpoint)
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("AKID", "secret")))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(true)
                        .build())
                .build();
        return R2ObjectStorageService.againstStub(mock(S3Client.class), presigner, "gpstore");
    }

    private static SignUploadRequest jpegSignRequest(long bytes) {
        SignUploadRequest request = new SignUploadRequest();
        request.setImageType(ImageKind.PRODUCT);
        request.setContentType("image/jpeg");
        request.setContentLength(bytes);
        return request;
    }

    private static boolean containsHeader(Map<String, String> headers, String name) {
        for (String key : headers.keySet()) {
            if (key.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }
}
