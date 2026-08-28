package com.gpstore.upload;

import com.gpstore.dto.request.SignUploadRequest;
import com.gpstore.dto.response.ConfirmedUploadResponse;
import com.gpstore.dto.response.SignedUploadResponse;
import com.gpstore.exception.BadRequestException;
import com.gpstore.exception.ConflictException;
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
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;

/**
 * Cloudflare R2 via the S3 API. All secrets stay on Hostinger. Flutter only
 * receives a short-lived PUT URL.
 */
@Service
public class R2ObjectStorageService {

    private static final Logger log = LoggerFactory.getLogger(R2ObjectStorageService.class);
    private static final String CACHE_CONTROL = "public, max-age=31536000, immutable";

    private final String accountId;
    private final String accessKeyId;
    private final String secretAccessKey;
    private final String bucket;
    private final String publicBaseUrl;

    private S3Client s3;
    private S3Presigner presigner;

    public R2ObjectStorageService(
            @Value("${r2.account-id:}") String accountId,
            @Value("${r2.access-key-id:}") String accessKeyId,
            @Value("${r2.secret-access-key:}") String secretAccessKey,
            @Value("${r2.bucket-name:}") String bucket,
            @Value("${r2.public-base-url:}") String publicBaseUrl) {
        this.accountId = trim(accountId);
        this.accessKeyId = trim(accessKeyId);
        this.secretAccessKey = trim(secretAccessKey);
        this.bucket = trim(bucket);
        this.publicBaseUrl = trimTrailingSlash(trim(publicBaseUrl));
    }

    @PostConstruct
    void start() {
        if (!isConfigured()) {
            log.info("R2 is not configured. Admin image upload stays off until R2_* is set on the VPS.");
            return;
        }
        URI endpoint = URI.create("https://" + accountId + ".r2.cloudflarestorage.com");
        StaticCredentialsProvider credentials = StaticCredentialsProvider.create(
                AwsBasicCredentials.create(accessKeyId, secretAccessKey));
        S3Configuration s3Config = S3Configuration.builder()
                .pathStyleAccessEnabled(true)
                .build();
        // R2 ignores AWS region; US_EAST_1 is the documented signer region.
        Region region = Region.US_EAST_1;
        this.s3 = S3Client.builder()
                .endpointOverride(endpoint)
                .region(region)
                .credentialsProvider(credentials)
                .serviceConfiguration(s3Config)
                .build();
        this.presigner = S3Presigner.builder()
                .endpointOverride(endpoint)
                .region(region)
                .credentialsProvider(credentials)
                .serviceConfiguration(s3Config)
                .build();
        URI publicUri = URI.create(publicBaseUrl);
        CatalogImageHosts.allow(publicUri.getHost());
        log.info("R2 object storage ready for bucket uploads (credentials not logged).");
    }

    @PreDestroy
    void stop() {
        if (presigner != null) {
            presigner.close();
        }
        if (s3 != null) {
            s3.close();
        }
    }

    public boolean isConfigured() {
        return !accountId.isBlank()
                && !accessKeyId.isBlank()
                && !secretAccessKey.isBlank()
                && !bucket.isBlank()
                && !publicBaseUrl.isBlank()
                && publicBaseUrl.startsWith("https://");
    }

    public String publicBaseUrl() {
        return publicBaseUrl;
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
        return new SignedUploadResponse(
                presigned.url().toString(),
                objectKey,
                publicUrl(objectKey),
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
        return new ConfirmedUploadResponse(key, publicUrl(key));
    }

    public void deletePublicUrl(String publicUrl) {
        if (!isConfigured() || publicUrl == null || publicUrl.isBlank()) {
            return;
        }
        String key = keyFromPublicUrl(publicUrl.trim());
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

    public String publicUrl(String objectKey) {
        return publicBaseUrl + "/" + objectKey;
    }

    String keyFromPublicUrl(String url) {
        String prefix = publicBaseUrl + "/";
        if (!url.startsWith(prefix)) {
            return null;
        }
        String key = url.substring(prefix.length());
        if (key.isBlank() || key.contains("..") || key.startsWith("/")) {
            return null;
        }
        return key.startsWith("gpstore/") ? key : null;
    }

    private String requireOwnedKey(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            throw new BadRequestException("objectKey is required.");
        }
        String key = objectKey.trim();
        if (key.contains("..") || key.startsWith("/") || key.contains("\\")) {
            throw new BadRequestException("Invalid object path.");
        }
        if (!key.startsWith("gpstore/products/") && !key.startsWith("gpstore/categories/")) {
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
                    "Image upload isn't configured yet - set R2_ACCOUNT_ID, R2_ACCESS_KEY_ID, "
                            + "R2_SECRET_ACCESS_KEY, R2_BUCKET_NAME, and R2_PUBLIC_BASE_URL "
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
