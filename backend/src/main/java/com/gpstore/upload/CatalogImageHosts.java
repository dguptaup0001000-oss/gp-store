package com.gpstore.upload;

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Extra image hosts allowed in catalogue URLs (the R2 public / CDN host).
 * Cloudinary remains readable so existing rows keep working during migration.
 */
public final class CatalogImageHosts {

    private static final Set<String> EXTRA = ConcurrentHashMap.newKeySet();

    private CatalogImageHosts() {}

    public static void allow(String host) {
        if (host == null || host.isBlank()) {
            return;
        }
        EXTRA.add(host.trim().toLowerCase(Locale.ROOT));
    }

    public static boolean isAllowed(String host) {
        if (host == null || host.isBlank()) {
            return false;
        }
        String normalised = host.toLowerCase(Locale.ROOT);
        if ("res.cloudinary.com".equals(normalised)) {
            return true;
        }
        if (normalised.endsWith(".r2.dev")) {
            return true;
        }
        return EXTRA.contains(normalised);
    }

    /** Test helper. Production never clears the R2 host once configured. */
    public static void clearForTests() {
        EXTRA.clear();
    }
}
