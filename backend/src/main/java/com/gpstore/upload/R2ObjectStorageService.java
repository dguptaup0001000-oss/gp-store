package com.gpstore.upload;

import com.gpstore.dto.request.SignUploadRequest;
import com.gpstore.dto.response.ConfirmedUploadResponse;
import com.gpstore.dto.response.R2ConnectionTestResponse;
import com.gpstore.dto.response.SignedUploadResponse;
import com.gpstore.entity.R2StagingObject;
import com.gpstore.exception.BadRequestException;
import com.gpstore.exception.ConflictException;
import com.gpstore.repository.R2StagingObjectRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;

/**
 * Cloudflare R2 via the S3 API. All secrets stay on Hostinger. The bucket
 * is private: Flutter receives short-lived PUT/GET URLs, never keys.
 */
@Service
public class R2ObjectStorageService {

    private static final Logger log = LoggerFactory.getLogger(R2ObjectStorageService.class);
    private static final String CACHE_CONTROL = "private, max-age=31536000, immutable";
    /** Must outlive CACHE_TTL_MS (default 10 minutes). Signed URLs are cached on catalogue DTOs. */
    static final int GET_TTL_SECONDS = 3600;

    /** 1×1 JPEG. Magic bytes only — used for the connection probe. */
    private static final byte[] TINY_JPEG = new byte[] {
            (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0x00, 0x10, 0x4A, 0x46,
            0x49, 0x46, 0x00, 0x01, 0x01, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00,
            (byte) 0xFF, (byte) 0xDB, 0x00, 0x43, 0x00, 0x08, 0x06, 0x06, 0x07, 0x06,
            0x05, 0x08, 0x07, 0x07, 0x07, 0x09, 0x09, 0x08, 0x0A, 0x0C, 0x14, 0x0D,
            0x0C, 0x0B, 0x0B, 0x0C, 0x19, 0x12, 0x13, 0x0F, 0x14, 0x1D, 0x1A, 0x1F,
            0x1E, 0x1D, 0x1A, 0x1C, 0x1C, 0x20, 0x24, 0x2E, 0x27, 0x20, 0x22, 0x2C,
            0x23, 0x1C, 0x1C, 0x28, 0x37, 0x29, 0x2C, 0x30, 0x31, 0x34, 0x34, 0x34,
            0x1F, 0x27, 0x39, 0x3D, 0x38, 0x32, 0x3C, 0x2E, 0x33, 0x34, 0x32,
            (byte) 0xFF, (byte) 0xC0, 0x00, 0x0B, 0x08, 0x00, 0x01, 0x00, 0x01, 0x01,
            0x01, 0x11, 0x00, (byte) 0xFF, (byte) 0xC4, 0x00, 0x14, 0x00, 0x01, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x03, (byte) 0xFF, (byte) 0xC4, 0x00, 0x14, 0x10, 0x01, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, (byte) 0xFF, (byte) 0xDA, 0x00, 0x08, 0x01, 0x01, 0x00,
            0x00, 0x3F, 0x00, 0x37, (byte) 0xFF, (byte) 0xD9
    };

    private final String accountId;
    private final String endpoint;
    private final String accessKeyId;
    private final String secretAccessKey;
    private final String bucket;
    private final String publicBaseUrl;
    private final R2StagingObjectRepository stagingObjects;

    private S3Client s3;
    private S3Presigner presigner;

    R2ObjectStorageService(
            String accountId,
            String endpoint,
            String accessKeyId,
            String secretAccessKey,
            String bucket,
            String publicBaseUrl) {
        this(accountId, endpoint, accessKeyId, secretAccessKey, bucket, publicBaseUrl, null);
    }

    public R2ObjectStorageService(
            @Value("${r2.account-id:}") String accountId,
            @Value("${r2.endpoint:}") String endpoint,
            @Value("${r2.access-key-id:}") String accessKeyId,
            @Value("${r2.secret-access-key:}") String secretAccessKey,
            @Value("${r2.bucket-name:}") String bucket,
            @Value("${r2.public-base-url:}") String publicBaseUrl,
            R2StagingObjectRepository stagingObjects) {
        this.accountId = trim(accountId);
        this.endpoint = trimTrailingSlash(trim(endpoint));
        this.accessKeyId = trim(accessKeyId);
        this.secretAccessKey = trim(secretAccessKey);
        this.bucket = trim(bucket);
        this.publicBaseUrl = trimTrailingSlash(trim(publicBaseUrl));
        this.stagingObjects = stagingObjects;
    }

    @PostConstruct
    void start() {
        if (!isConfigured()) {
            log.info("R2 is not configured. Admin image upload stays off until R2_* is set on the VPS.");
            return;
        }
        URI apiEndpoint = resolvedEndpoint();
        StaticCredentialsProvider credentials = StaticCredentialsProvider.create(
                AwsBasicCredentials.create(accessKeyId, secretAccessKey));
        S3Configuration s3Config = S3Configuration.builder()
                .pathStyleAccessEnabled(true)
                .build();
        Region region = r2Region();
        this.s3 = S3Client.builder()
                .endpointOverride(apiEndpoint)
                .region(region)
                .credentialsProvider(credentials)
                .serviceConfiguration(s3Config)
                .build();
        this.presigner = S3Presigner.builder()
                .endpointOverride(apiEndpoint)
                .region(region)
                .credentialsProvider(credentials)
                .serviceConfiguration(s3Config)
                .build();
        if (!publicBaseUrl.isBlank() && publicBaseUrl.startsWith("https://")) {
            URI publicUri = URI.create(publicBaseUrl);
            CatalogImageHosts.allow(publicUri.getHost());
        }
        CatalogImageDelivery.bind(this);
        log.info("R2 object storage ready for private-bucket uploads (credentials not logged).");
    }

    @PreDestroy
    void stop() {
        CatalogImageDelivery.unbindForTests();
        if (presigner != null) {
            presigner.close();
        }
        if (s3 != null) {
            s3.close();
        }
    }

    public boolean isConfigured() {
        return hasApiEndpoint()
                && !accessKeyId.isBlank()
                && !secretAccessKey.isBlank()
                && !bucket.isBlank();
    }

    public SignedUploadResponse sign(SignUploadRequest request) {
        UploadPolicy.requireAllowedUpload(
                request.getImageType(), request.getContentType(), request.getContentLength());
        requireConfigured();
        String contentType = UploadPolicy.normalizedContentType(request.getContentType());
        String objectKey = UploadPolicy.objectKey(
                request.getImageType(), request.getOwnerId(), contentType);
        PutObjectRequest put = PutObjectRequest.builder()
                .bucket(bucket)
                .key(objectKey)
                .contentType(contentType)
                .contentLength(request.getContentLength())
                .cacheControl(CACHE_CONTROL)
                .build();
        PresignedPutObjectRequest presigned = presigner.presignPutObject(
                PutObjectPresignRequest.builder()
                        .signatureDuration(Duration.ofSeconds(UploadPolicy.SIGN_TTL_SECONDS))
                        .putObjectRequest(put)
                        .build());
        long expiresAt = System.currentTimeMillis() / 1000 + UploadPolicy.SIGN_TTL_SECONDS;
        rememberStaging(objectKey);
        return new SignedUploadResponse(
                presigned.url().toString(),
                objectKey,
                CatalogImageRefs.storedRef(objectKey),
                "PUT",
                Map.of("Content-Type", contentType),
                expiresAt);
    }

    public ConfirmedUploadResponse confirm(String objectKey) {
        String key = requireOwnedKey(objectKey);
        requireConfigured();
        HeadObjectResponse head;
        try {
            head = s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
        } catch (NoSuchKeyException ex) {
            throw new BadRequestException("That upload did not arrive. Try again.");
        } catch (S3Exception ex) {
            if (ex.statusCode() == 404) {
                throw new BadRequestException("That upload did not arrive. Try again.");
            }
            log.warn("R2 confirm failed for an object key (key not logged).");
            throw new BadRequestException("That upload could not be verified. Try again.");
        } catch (RuntimeException ex) {
            log.warn("R2 confirm failed for an object key (key not logged).");
            throw new BadRequestException("That upload could not be verified. Try again.");
        }
        long size = head.contentLength() == null ? 0L : head.contentLength();
        if (size <= 0 || size > UploadPolicy.DEFAULT_MAX_BYTES) {
            tryDelete(key);
            throw new BadRequestException("That upload is missing or too large.");
        }
        String type = head.contentType() == null ? "" : head.contentType().toLowerCase(Locale.ROOT);
        if (type.contains("svg") || type.contains("html") || type.contains("javascript")) {
            tryDelete(key);
            throw new BadRequestException("That file type is not allowed.");
        }
        if (!hasAllowedImageMagic(key)) {
            tryDelete(key);
            throw new BadRequestException("That file is not a JPEG, PNG, or WebP image.");
        }
        if (UploadPolicy.isStagingKey(key)) {
            String permanent = UploadPolicy.permanentKeyFromStaging(key);
            try {
                s3.copyObject(CopyObjectRequest.builder()
                        .sourceBucket(bucket)
                        .sourceKey(key)
                        .destinationBucket(bucket)
                        .destinationKey(permanent)
                        .build());
            } catch (RuntimeException ex) {
                log.warn("R2 staging promote failed (key not logged).");
                throw new BadRequestException("That upload could not be verified. Try again.");
            }
            tryDelete(key);
            forgetStaging(key);
            key = permanent;
        }
        return new ConfirmedUploadResponse(key, signGet(key));
    }

    /**
     * Sweeper entry point. Refuses permanent catalogue keys so a confirmed
     * image cannot be reclaimed.
     */
    public void deleteStagingObject(String objectKey) {
        String key = requireOwnedKey(objectKey);
        if (!UploadPolicy.isStagingKey(key)) {
            log.warn("Refusing to delete a non-staging R2 object from the sweeper.");
            return;
        }
        if (s3 != null) {
            tryDelete(key);
        }
        forgetStaging(key);
    }

    private void rememberStaging(String objectKey) {
        if (stagingObjects == null || !UploadPolicy.isStagingKey(objectKey)) {
            return;
        }
        stagingObjects.save(new R2StagingObject(objectKey, Instant.now()));
    }

    private void forgetStaging(String objectKey) {
        if (stagingObjects == null) {
            return;
        }
        stagingObjects.deleteById(objectKey);
    }

    public void deletePublicUrl(String storedOrUrl) {
        if (!isConfigured() || storedOrUrl == null || storedOrUrl.isBlank()) {
            return;
        }
        String key = CatalogImageRefs.objectKeyFrom(storedOrUrl.trim());
        if (key == null) {
            key = keyFromOptionalPublicBase(storedOrUrl.trim());
        }
        if (key == null) {
            return;
        }
        tryDelete(key);
    }

    public void putBytes(String objectKey, String contentType, byte[] bytes) {
        requireConfigured();
        String key = requireOwnedKey(objectKey);
        UploadPolicy.requireAllowedUpload(ImageKind.PRODUCT, contentType, bytes.length);
        s3.putObject(
                PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(key)
                        .contentType(UploadPolicy.normalizedContentType(contentType))
                        .cacheControl(CACHE_CONTROL)
                        .build(),
                RequestBody.fromBytes(bytes));
    }

    public String signGet(String objectKey) {
        requireConfigured();
        String key = requireOwnedKey(objectKey);
        if (!publicBaseUrl.isBlank() && publicBaseUrl.startsWith("https://")) {
            return publicBaseUrl + "/" + key;
        }
        GetObjectRequest get = GetObjectRequest.builder().bucket(bucket).key(key).build();
        PresignedGetObjectRequest presigned = presigner.presignGetObject(
                GetObjectPresignRequest.builder()
                        .signatureDuration(Duration.ofSeconds(GET_TTL_SECONDS))
                        .getObjectRequest(get)
                        .build());
        return presigned.url().toString();
    }

    public R2ConnectionTestResponse connectionTest() {
        requireConfigured();
        String key = UploadPolicy.objectKey(ImageKind.PRODUCT, null, "image/jpeg");
        boolean uploaded = false;
        boolean verified = false;
        boolean deleted = false;
        try {
            putBytes(key, "image/jpeg", TINY_JPEG);
            uploaded = true;
            HeadObjectResponse head = s3.headObject(
                    HeadObjectRequest.builder().bucket(bucket).key(key).build());
            long size = head.contentLength() == null ? 0L : head.contentLength();
            // Head is enough. Many R2 API tokens omit ListBucket (403).
            verified = size == TINY_JPEG.length;
            tryDelete(key);
            deleted = !objectExists(key);
            boolean ok = uploaded && verified && deleted;
            return new R2ConnectionTestResponse(
                    true, uploaded, verified, deleted, ok,
                    ok ? "R2 connection test passed." : "R2 connection test did not fully complete.");
        } catch (RuntimeException ex) {
            tryDelete(key);
            log.warn("R2 connection test failed (credentials not logged).");
            return new R2ConnectionTestResponse(
                    true, uploaded, verified, deleted, false,
                    "R2 connection test failed. Check bucket name, endpoint, and API token on the VPS.");
        }
    }

    private boolean hasAllowedImageMagic(String key) {
        try (var in = s3.getObject(GetObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .range("bytes=0-15")
                .build())) {
            byte[] prefix = in.readNBytes(16);
            String detected = UploadPolicy.detectContentType(prefix);
            return detected != null && UploadPolicy.ALLOWED_CONTENT_TYPES.contains(detected);
        } catch (RuntimeException | java.io.IOException ex) {
            log.warn("R2 magic-byte check failed (key not logged).");
            return false;
        }
    }

    boolean objectExists(String key) {
        try {
            s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
            return true;
        } catch (NoSuchKeyException ex) {
            return false;
        } catch (S3Exception ex) {
            return ex.statusCode() != 404;
        }
    }

    String keyFromOptionalPublicBase(String url) {
        if (publicBaseUrl.isBlank() || !url.startsWith(publicBaseUrl + "/")) {
            return null;
        }
        return CatalogImageRefs.ownedKeyOrNull(url.substring(publicBaseUrl.length() + 1));
    }

    private URI resolvedEndpoint() {
        String value = endpoint;
        if (value.isBlank() && !accountId.isBlank()) {
            value = "https://" + accountId + ".r2.cloudflarestorage.com";
        }
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException ex) {
            throw new ConflictException("R2_ENDPOINT is not a valid HTTPS URL.");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
            throw new ConflictException("R2_ENDPOINT must be HTTPS.");
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (!host.endsWith(".r2.cloudflarestorage.com")) {
            throw new ConflictException("R2_ENDPOINT host must be a Cloudflare R2 API host.");
        }
        if (uri.getUserInfo() != null) {
            throw new ConflictException("R2_ENDPOINT must not include credentials.");
        }
        return uri;
    }

    private boolean hasApiEndpoint() {
        if (!endpoint.isBlank()) {
            return true;
        }
        return !accountId.isBlank();
    }

    private static Region r2Region() {
        try {
            return Region.of("auto");
        } catch (RuntimeException ex) {
            return Region.US_EAST_1;
        }
    }

    private String requireOwnedKey(String objectKey) {
        String key = CatalogImageRefs.ownedKeyOrNull(objectKey);
        if (key == null) {
            throw new BadRequestException("Invalid object path.");
        }
        return key;
    }

    private void tryDelete(String key) {
        try {
            s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
        } catch (RuntimeException ex) {
            log.warn("R2 delete failed (key not logged).");
        }
    }

    private void requireConfigured() {
        if (!isConfigured() || s3 == null || presigner == null) {
            throw new ConflictException(
                    "Image upload isn't configured yet - set R2_ACCOUNT_ID or R2_ENDPOINT, "
                            + "R2_ACCESS_KEY_ID, R2_SECRET_ACCESS_KEY, and R2_BUCKET_NAME "
                            + "on the Hostinger VPS (see backend/.env.example).");
        }
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static String trimTrailingSlash(String value) {
        if (value.endsWith("/")) {
            return value.substring(0, value.length() - 1);
        }
        return value;
    }
}
