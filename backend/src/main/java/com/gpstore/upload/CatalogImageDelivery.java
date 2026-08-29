package com.gpstore.upload;

/**
 * Turns a stored catalogue image reference into a URL the phone can load.
 * R2 objects stay in a private bucket. When a Worker base URL is configured
 * the client gets a stable CDN URL; otherwise a short-lived signed GET.
 * Cloudinary and other HTTPS rows pass through. Bytes never transit Tomcat.
 */
public final class CatalogImageDelivery {

    private static volatile R2ObjectStorageService r2;

    private CatalogImageDelivery() {}

    static void bind(R2ObjectStorageService service) {
        r2 = service;
    }

    static void unbindForTests() {
        r2 = null;
    }

    public static String forClient(String stored) {
        if (stored == null || stored.isBlank()) {
            return stored;
        }
        String key = CatalogImageRefs.objectKeyFrom(stored);
        if (key == null) {
            return stored;
        }
        R2ObjectStorageService service = r2;
        if (service == null || !service.isConfigured()) {
            return stored;
        }
        try {
            return service.deliveryUrl(key);
        } catch (RuntimeException ex) {
            return stored;
        }
    }
}
