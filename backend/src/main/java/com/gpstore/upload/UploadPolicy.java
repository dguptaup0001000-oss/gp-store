package com.gpstore.upload;

import com.gpstore.exception.BadRequestException;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Server-side rules for catalogue image uploads. Object keys are generated
 * here so the client never chooses a path. Flutter never sees R2 credentials.
 */
public final class UploadPolicy {

    public static final int DEFAULT_MAX_BYTES = 4 * 1024 * 1024;
    public static final int SIGN_TTL_SECONDS = 300;
    /** One HTTP request may sign or confirm at most this many objects. */
    public static final int BATCH_MAX = 20;

    public static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "image/jpeg",
            "image/png",
            "image/webp");

    private static final Set<String> REJECTED_TYPES = Set.of(
            "application/octet-stream",
            "application/x-msdownload",
            "application/zip",
            "application/javascript",
            "text/html",
            "image/svg+xml",
            "text/xml");

    private static final Map<String, String> EXTENSIONS = Map.of(
            "image/jpeg", ".jpg",
            "image/png", ".png",
            "image/webp", ".webp");

    private UploadPolicy() {}

    public static void requireAllowedUpload(
            ImageKind kind, String contentType, long contentLength) {
        if (kind == null) {
            throw new BadRequestException("imageType is required (PRODUCT or CATEGORY).");
        }
        if (contentType == null || contentType.isBlank()) {
            throw new BadRequestException("contentType is required.");
        }
        String type = contentType.trim().toLowerCase(Locale.ROOT);
        int semicolon = type.indexOf(';');
        if (semicolon > 0) {
            type = type.substring(0, semicolon).trim();
        }
        if (REJECTED_TYPES.contains(type) || type.endsWith("+xml") || type.contains("svg")) {
            throw new BadRequestException("That file type is not allowed.");
        }
        if (!ALLOWED_CONTENT_TYPES.contains(type)) {
            throw new BadRequestException("Only JPEG, PNG, or WebP images are allowed.");
        }
        if (contentLength <= 0) {
            throw new BadRequestException("contentLength must be the image size in bytes.");
        }
        if (contentLength > kind.maxBytes()) {
            throw new BadRequestException(
                    "That photo is too large. Maximum is " + (kind.maxBytes() / (1024 * 1024))
                            + " MB.");
        }
    }

    public static String normalizedContentType(String contentType) {
        String type = contentType.trim().toLowerCase(Locale.ROOT);
        int semicolon = type.indexOf(';');
        if (semicolon > 0) {
            type = type.substring(0, semicolon).trim();
        }
        return type;
    }

    public static final String STAGING_ROOT = "gpstore/staging/";

    /**
     * Staging key for a client PUT. Confirm copies this to
     * {@link #permanentObjectKey} so a lifecycle rule can expire leftovers.
     */
    public static String objectKey(ImageKind kind, Long ownerId, String contentType) {
        return STAGING_ROOT + relativeObjectKey(kind, ownerId, contentType);
    }

    /**
     * Permanent catalogue key. Used after confirm and for operator migrations
     * of images that are already known-good.
     */
    public static String permanentObjectKey(ImageKind kind, Long ownerId, String contentType) {
        return "gpstore/" + relativeObjectKey(kind, ownerId, contentType);
    }

    public static boolean isStagingKey(String key) {
        return key != null && key.startsWith(STAGING_ROOT);
    }

    public static String permanentKeyFromStaging(String stagingKey) {
        if (!isStagingKey(stagingKey)) {
            throw new BadRequestException("Invalid object path.");
        }
        String permanent = "gpstore/" + stagingKey.substring(STAGING_ROOT.length());
        if (isStagingKey(permanent) || CatalogImageRefs.ownedKeyOrNull(permanent) == null) {
            throw new BadRequestException("Invalid object path.");
        }
        return permanent;
    }

    private static String relativeObjectKey(ImageKind kind, Long ownerId, String contentType) {
        requireAllowedUpload(kind, contentType, 1);
        String ext = EXTENSIONS.get(normalizedContentType(contentType));
        String owner = "new";
        if (ownerId != null) {
            if (ownerId <= 0) {
                throw new BadRequestException("ownerId must be a positive id.");
            }
            owner = Long.toString(ownerId);
        }
        return kind.storageFolder() + "/" + owner + "/original/" + UUID.randomUUID() + ext;
    }

    /**
     * MIME from magic bytes, not the client-supplied filename or Content-Type
     * header. SVG/HTML/empty buffers return null.
     */
    public static String detectContentType(byte[] bytes) {
        if (bytes == null || bytes.length < 12) {
            return null;
        }
        if ((bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8 && (bytes[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if ((bytes[0] & 0xFF) == 0x89 && bytes[1] == 0x50 && bytes[2] == 0x4E && bytes[3] == 0x47) {
            return "image/png";
        }
        if (bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') {
            return "image/webp";
        }
        return null;
    }
}
