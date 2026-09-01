package com.gpstore.profile;

import com.gpstore.dto.response.SignedUploadResponse;
import com.gpstore.entity.Customer;
import com.gpstore.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A customer's own profile photo.
 *
 * Under /api/customers/me/** so SecurityConfig's {@code anyRequest()
 * .authenticated()} covers it - deliberately NOT under /api/uploads, which is
 * admin-only because it writes catalogue images. Every method acts on
 * {@code currentUser.customerId()} and takes no customer id from the caller,
 * so there is no id here for anyone to tamper with.
 */
@RestController
@RequestMapping("/api/customers/me/photo")
public class ProfilePhotoController {

    private final ProfilePhotoService photos;
    private final CurrentUser currentUser;

    public ProfilePhotoController(ProfilePhotoService photos, CurrentUser currentUser) {
        this.photos = photos;
        this.currentUser = currentUser;
    }

    /** Step 1: a short-lived URL to PUT the image bytes to. */
    @PostMapping("/sign")
    public SignedUploadResponse sign(@Valid @RequestBody SignPhotoRequest request) {
        return photos.sign(
                currentUser.customerId(), request.getContentType(), request.getContentLength());
    }

    /** Step 2: attach the uploaded object. Returns the updated profile. */
    @PutMapping
    public Customer attach(@Valid @RequestBody AttachPhotoRequest request) {
        return photos.attach(currentUser.customerId(), request.getObjectKey());
    }

    @DeleteMapping
    public Customer remove() {
        return photos.remove(currentUser.customerId());
    }

    public static class SignPhotoRequest {
        @NotBlank
        private String contentType;

        /**
         * Declared up front so an oversized photo is refused before any bytes
         * are uploaded, rather than after the customer has spent their mobile
         * data sending it.
         */
        @Positive
        private long contentLength;

        public String getContentType() {
            return contentType;
        }

        public void setContentType(String contentType) {
            this.contentType = contentType;
        }

        public long getContentLength() {
            return contentLength;
        }

        public void setContentLength(long contentLength) {
            this.contentLength = contentLength;
        }
    }

    public static class AttachPhotoRequest {
        /**
         * The key returned by {@code /sign}. Checked against the caller's own
         * id before use - see ProfilePhotoService.
         */
        @NotBlank
        private String objectKey;

        public String getObjectKey() {
            return objectKey;
        }

        public void setObjectKey(String objectKey) {
            this.objectKey = objectKey;
        }
    }
}
