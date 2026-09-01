package com.gpstore.profile;

import com.gpstore.exception.BadRequestException;
import com.gpstore.upload.ImageKind;
import com.gpstore.upload.UploadPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Whose photo is this?
 *
 * Uploading is two requests: sign, then attach. Attach carries an object key
 * chosen by the client, and nothing about HTTP stops a customer sending a key
 * that is not theirs. Without the check these tests cover, customer 2 could
 * attach customer 1's freshly-uploaded photo to their own profile - and,
 * because a rejected key and an accepted one would answer differently, could
 * also use attach to probe which keys exist.
 *
 * The check is exact rather than a prefix match on purpose. "Starts with the
 * right owner id" is not the same as "is that owner's key": 12 is not a
 * prefix-safe stand-in for 1, and a key with extra path segments is not a key
 * this service issued.
 */
@DisplayName("A customer can only attach their own upload")
class ProfilePhotoOwnershipTest {

    private static String keyFor(long customerId) {
        return "gpstore/staging/profiles/" + customerId
                + "/original/1f0c3e2a-0000-4000-8000-000000000001.jpg";
    }

    @Test
    @DisplayName("the owner's own key is accepted")
    void ownKeyIsAccepted() {
        assertDoesNotThrow(
                () -> UploadPolicy.requireOwnedBy(keyFor(7), ImageKind.PROFILE, 7));
    }

    @Test
    @DisplayName("another customer's key is refused")
    void otherCustomersKeyIsRefused() {
        assertThrows(BadRequestException.class,
                () -> UploadPolicy.requireOwnedBy(keyFor(8), ImageKind.PROFILE, 7));
    }

    @Test
    @DisplayName("an id that merely starts with the owner's is refused")
    void prefixCollisionIsRefused() {
        // Customer 1 must not be able to claim customer 12's or 100's uploads.
        // A naive startsWith(".../profiles/" + id) would accept both.
        assertThrows(BadRequestException.class,
                () -> UploadPolicy.requireOwnedBy(keyFor(12), ImageKind.PROFILE, 1));
        assertThrows(BadRequestException.class,
                () -> UploadPolicy.requireOwnedBy(keyFor(100), ImageKind.PROFILE, 1));
    }

    @Test
    @DisplayName("a catalogue key cannot be attached to a profile")
    void catalogueKeyIsRefused() {
        // A product photo lives under a different folder. Accepting it here
        // would let a customer point their avatar at catalogue storage.
        assertThrows(BadRequestException.class, () -> UploadPolicy.requireOwnedBy(
                "gpstore/staging/products/7/original/a.jpg", ImageKind.PROFILE, 7));
    }

    @Test
    @DisplayName("a permanent key cannot be re-attached")
    void permanentKeyIsRefused() {
        // Only a staging key is confirmable. A permanent key is one that has
        // already been attached - to this customer or to someone else.
        assertThrows(BadRequestException.class, () -> UploadPolicy.requireOwnedBy(
                "gpstore/profiles/7/original/a.jpg", ImageKind.PROFILE, 7));
    }

    @Test
    @DisplayName("path traversal in the key is refused")
    void traversalIsRefused() {
        assertThrows(BadRequestException.class, () -> UploadPolicy.requireOwnedBy(
                "gpstore/staging/profiles/7/original/../../8/original/a.jpg",
                ImageKind.PROFILE, 7));
    }

    @Test
    @DisplayName("a missing key is refused rather than treated as empty")
    void blankKeyIsRefused() {
        assertThrows(BadRequestException.class,
                () -> UploadPolicy.requireOwnedBy(null, ImageKind.PROFILE, 7));
        assertThrows(BadRequestException.class,
                () -> UploadPolicy.requireOwnedBy("   ", ImageKind.PROFILE, 7));
    }

    @Test
    @DisplayName("rejection never says whether the key exists")
    void rejectionDoesNotLeakExistence() {
        // Same message for "not yours" and "not a key at all", so attach
        // cannot be used to enumerate other customers' uploads.
        String other = assertThrows(BadRequestException.class,
                () -> UploadPolicy.requireOwnedBy(keyFor(8), ImageKind.PROFILE, 7)).getMessage();
        String nonsense = assertThrows(BadRequestException.class,
                () -> UploadPolicy.requireOwnedBy("nonsense", ImageKind.PROFILE, 7)).getMessage();

        assertEquals(other, nonsense);
    }

    @Test
    @DisplayName("a profile photo is capped tighter than a catalogue image")
    void profilePhotosAreCappedTighter() {
        // A phone camera original is 4-8 MB and gets displayed at 96px.
        assertEquals(2 * 1024 * 1024, ImageKind.PROFILE.maxBytes());
        assertTrue(ImageKind.PROFILE.maxBytes() < ImageKind.PRODUCT.maxBytes());

        assertThrows(BadRequestException.class, () -> UploadPolicy.requireAllowedUpload(
                ImageKind.PROFILE, "image/jpeg", 5 * 1024 * 1024));
    }
}
