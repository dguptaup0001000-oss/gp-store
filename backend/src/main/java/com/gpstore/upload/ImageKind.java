package com.gpstore.upload;

/**
 * Catalogue image kinds that the admin app may upload. Profile, review, and
 * banner photos are not stored in this shop today — do not invent them here.
 */
public enum ImageKind {
    PRODUCT("gpstore/products", UploadPolicy.DEFAULT_MAX_BYTES),
    CATEGORY("gpstore/categories", UploadPolicy.DEFAULT_MAX_BYTES);

    private final String prefix;
    private final int maxBytes;

    ImageKind(String prefix, int maxBytes) {
        this.prefix = prefix;
        this.maxBytes = maxBytes;
    }

    public String prefix() {
        return prefix;
    }

    /** Last path segment ({@code products} or {@code categories}). */
    public String storageFolder() {
        int slash = prefix.lastIndexOf('/');
        return slash < 0 ? prefix : prefix.substring(slash + 1);
    }

    public int maxBytes() {
        return maxBytes;
    }
}
