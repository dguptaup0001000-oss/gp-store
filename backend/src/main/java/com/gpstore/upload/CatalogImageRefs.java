package com.gpstore.upload;

import java.net.URI;
import java.util.Locale;

/**
 * Stable catalogue image references. The database stores either a legacy
 * HTTPS URL (Cloudinary / Open Food Facts) or {@code r2:<objectKey>}.
 * Presigned GET URLs are delivery-only and must not be persisted.
 */
public final class CatalogImageRefs {

    public static final String R2_PREFIX = "r2:";
    public static final int MAX_INCOMING_LENGTH = 2000;

    private CatalogImageRefs() {}

    public static String storedRef(String objectKey) {
        return R2_PREFIX + objectKey;
    }

    public static boolean isStoredR2Ref(String value) {
        return objectKeyFrom(value) != null;
    }

    /**
     * Object key if this string points at an R2 catalogue object; otherwise
     * null (Cloudinary and other HTTPS hosts).
     */
    public static String objectKeyFrom(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.startsWith(R2_PREFIX)) {
            return ownedKeyOrNull(trimmed.substring(R2_PREFIX.length()));
        }
        if (trimmed.startsWith("gpstore/products/") || trimmed.startsWith("gpstore/categories/")) {
            return ownedKeyOrNull(trimmed);
        }
        URI uri;
        try {
            uri = URI.create(trimmed);
        } catch (IllegalArgumentException ex) {
            return null;
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getPath() == null) {
            return null;
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        boolean r2Api = host.endsWith(".r2.cloudflarestorage.com");
        boolean allowedDelivery = CatalogImageHosts.isAllowed(host);
        if (!r2Api && !allowedDelivery) {
            return null;
        }
        String path = uri.getPath();
        int products = path.indexOf("/gpstore/products/");
        int categories = path.indexOf("/gpstore/categories/");
        int at = products >= 0 ? products : categories;
        if (at < 0) {
            return null;
        }
        String key = path.substring(at + 1);
        if (key.contains("?") || key.contains("#")) {
            int cut = key.contains("?") ? key.indexOf('?') : key.indexOf('#');
            key = key.substring(0, cut);
        }
        return ownedKeyOrNull(key);
    }

    public static String canonicalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        String key = objectKeyFrom(trimmed);
        if (key != null) {
            return storedRef(key);
        }
        return trimmed;
    }

    static String ownedKeyOrNull(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        String normalised = key.trim();
        if (normalised.contains("..") || normalised.startsWith("/") || normalised.contains("\\")
                || normalised.contains(" ")) {
            return null;
        }
        if (normalised.contains("//")) {
            return null;
        }
        String lower = normalised.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("gpstore/products/") && !lower.startsWith("gpstore/categories/")) {
            return null;
        }
        if (lower.contains("%") || lower.contains("\r") || lower.contains("\n")) {
            return null;
        }
        return normalised;
    }
}
