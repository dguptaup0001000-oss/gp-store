package com.gpstore.upload;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CatalogImageRefsTest {

    @Test
    void r2RefsAndObjectKeysAreAccepted() {
        assertEquals(
                "gpstore/products/1/original/a.jpg",
                CatalogImageRefs.objectKeyFrom("r2:gpstore/products/1/original/a.jpg"));
        assertEquals(
                "gpstore/categories/4/original/b.webp",
                CatalogImageRefs.objectKeyFrom("gpstore/categories/4/original/b.webp"));
        assertEquals(
                "r2:gpstore/products/1/original/a.jpg",
                CatalogImageRefs.canonicalize("gpstore/products/1/original/a.jpg"));
    }

    @Test
    void pathTraversalAndForeignHostsAreRejected() {
        assertNull(CatalogImageRefs.objectKeyFrom("r2:../etc/passwd"));
        assertNull(CatalogImageRefs.objectKeyFrom("r2:gpstore/products/../x.jpg"));
        assertNull(CatalogImageRefs.objectKeyFrom("https://evil.example/gpstore/products/1/original/a.jpg"));
        assertNull(CatalogImageRefs.objectKeyFrom("javascript:alert(1)"));
    }

    @Test
    void signedGetPathIsCanonicalisedToAStoredRef() {
        String signed = "https://example.r2.cloudflarestorage.com/gp-store-images/gpstore/products/3/original/a.jpg"
                + "?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Signature=abc";
        assertEquals("r2:gpstore/products/3/original/a.jpg", CatalogImageRefs.canonicalize(signed));
    }
}
