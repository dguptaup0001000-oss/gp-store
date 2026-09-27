package com.gpstore.demand;

import com.gpstore.dto.response.ConfirmedUploadResponse;
import com.gpstore.security.CurrentUser;
import com.gpstore.upload.ImageKind;
import com.gpstore.upload.R2ObjectStorageService;
import com.gpstore.upload.UploadPolicy;
import org.springframework.stereotype.Service;

/**
 * Confirms a customer-owned controlled upload for an optional demand photo.
 * The owner comes from the JWT, never from the request.
 */
@Service
public class DemandPhotoService {
    private final R2ObjectStorageService storage;
    private final CurrentUser currentUser;

    public DemandPhotoService(R2ObjectStorageService storage, CurrentUser currentUser) {
        this.storage = storage;
        this.currentUser = currentUser;
    }

    public PhotoView confirm(String objectKey) {
        long customerId = currentUser.customerId();
        UploadPolicy.requireOwnedBy(objectKey, ImageKind.PROFILE, customerId);
        ConfirmedUploadResponse confirmed = storage.confirm(objectKey);
        return new PhotoView(confirmed.getImageRef());
    }

    public record PhotoView(String photoUrl) {}
}
