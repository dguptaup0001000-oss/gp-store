package com.gpstore.catalog;

import com.gpstore.exception.BadRequestException;
import com.gpstore.upload.CatalogImageHosts;

import java.net.URI;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * URLs that this shop will store and later hand to customers' phones.
 *
 * Bytes never pass through this backend (admin uploads go to object storage
 * with a short-lived signed URL). What we persist is a string that the
 * storefront will load, so the job here is to refuse javascript:, data:,
 * http://, credentialed URLs, and lookalike hosts.
 *
 * Image URLs must be HTTPS on an allowed catalogue host (Cloudinary
 * legacy rows) or a stored R2 object reference {@code r2:gpstore/...}.
 * Presigned R2 GET URLs are accepted on write and canonicalised to the
 * stored reference so the private bucket never needs public access.
 */
public final class CatalogUrlValidator {

    public static final String CLOUDINARY_HOST = "res.cloudinary.com";
    public static final int MAX_LENGTH = 500;

    public static final String IMAGE_MESSAGE =
            "Image URLs must be HTTPS links on the shop image host, or an R2 object reference.";
    public static final String MODEL_MESSAGE =
            "3D model URLs must be HTTPS links on a public host, without credentials.";

    private static final Pattern IPV4 = Pattern.compile(
            "^(?:\\d{1,3}\\.){3}\\d{1,3}$");

    private CatalogUrlValidator() {}

    public static boolean isAllowedImageUrl(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        String canonical = com.gpstore.upload.CatalogImageRefs.canonicalize(url.trim());
        if (com.gpstore.upload.CatalogImageRefs.isStoredR2Ref(canonical)) {
            String key = com.gpstore.upload.CatalogImageRefs.objectKeyFrom(canonical);
            return key != null && !com.gpstore.upload.UploadPolicy.isStagingKey(key);
        }
        URI uri = parseHttpsPublic(canonical);
        return uri != null && CatalogImageHosts.isAllowed(uri.getHost());
    }

    public static boolean isAllowedModel3dUrl(String url) {
        return parseHttpsPublic(url) != null;
    }

    /**
     * Null, blank, or an allowed catalogue HTTPS URL. Anything else is a 400.
     * Blank is allowed: a variant with no photo is a normal product.
     */
    public static void requireAllowedImageUrlOrEmpty(String url) {
        if (url == null || url.isBlank()) {
            return;
        }
        String trimmed = url.trim();
        if (trimmed.length() > com.gpstore.upload.CatalogImageRefs.MAX_INCOMING_LENGTH) {
            throw new BadRequestException(
                    "URL is too long (" + trimmed.length()
                            + " characters, limit " + com.gpstore.upload.CatalogImageRefs.MAX_INCOMING_LENGTH + ").");
        }
        String canonical = com.gpstore.upload.CatalogImageRefs.canonicalize(trimmed);
        rejectIfTooLong(canonical);
        if (!isAllowedImageUrl(canonical)) {
            throw new BadRequestException(IMAGE_MESSAGE);
        }
    }

    /**
     * Null, blank, or a public HTTPS URL. Anything else is a 400.
     */
    public static void requireAllowedModel3dUrl(String url) {
        if (url == null || url.isBlank()) {
            return;
        }
        String trimmed = url.trim();
        rejectIfTooLong(trimmed);
        if (!isAllowedModel3dUrl(trimmed)) {
            throw new BadRequestException(MODEL_MESSAGE);
        }
    }

    public static String trimToNull(String url) {
        if (url == null) {
            return null;
        }
        String trimmed = url.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        String canonical = com.gpstore.upload.CatalogImageRefs.canonicalize(trimmed);
        return canonical.isEmpty() ? null : canonical;
    }

    private static void rejectIfTooLong(String url) {
        if (url.length() > MAX_LENGTH) {
            throw new BadRequestException(
                    "URL is too long (" + url.length()
                            + " characters, limit " + MAX_LENGTH + ").");
        }
    }

    /**
     * HTTPS, no userinfo, a DNS hostname that is not loopback or RFC1918.
     * Returns null when the string is not a URL we will store.
     */
    static URI parseHttpsPublic(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            return null;
        }
        if (uri.getUserInfo() != null) {
            return null;
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return null;
        }
        String normalised = host.toLowerCase(Locale.ROOT);
        if (isPrivateOrLocalHost(normalised)) {
            return null;
        }
        return uri;
    }

    private static boolean isPrivateOrLocalHost(String host) {
        if ("localhost".equals(host)
                || host.endsWith(".localhost")
                || host.endsWith(".local")
                || "127.0.0.1".equals(host)
                || "0.0.0.0".equals(host)
                || "::1".equals(host)
                || host.contains(":")) {
            return true;
        }
        if (IPV4.matcher(host).matches()) {
            return true;
        }
        return host.startsWith("10.")
                || host.startsWith("192.168.")
                || host.startsWith("169.254.")
                || host.matches("^172\\.(1[6-9]|2[0-9]|3[0-1])\\..*");
    }
}
