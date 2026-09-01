package com.gpstore.upload;

/**
 * Image kinds this shop stores. Review and banner photos are not among them —
 * do not invent them here.
 *
 * PRODUCT and CATEGORY are catalogue images and only an admin may upload
 * them. PROFILE is different in kind: the uploader is an ordinary customer
 * uploading their own avatar, which is why it is reached through
 * /api/customers/me/photo rather than the admin-only /api/uploads, and why
 * the owner segment of its object key is taken from the authenticated
 * principal rather than from the request body.
 */
public enum ImageKind {
    PRODUCT("gpstore/products", UploadPolicy.DEFAULT_MAX_BYTES),
    CATEGORY("gpstore/categories", UploadPolicy.DEFAULT_MAX_BYTES),
    /**
     * A customer's own avatar. Capped tighter than a catalogue photo at 2 MB:
     * it is displayed at 96px, a modern phone camera produces 4-8 MB, and the
     * cap is what stops every signup costing several megabytes of storage for
     * a picture nobody will ever see at full size.
     */
    PROFILE("gpstore/profiles", 2 * 1024 * 1024);

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
