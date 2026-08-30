package com.gpstore.controller;

import com.gpstore.dto.request.ConfirmBatchRequest;
import com.gpstore.dto.request.ConfirmUploadRequest;
import com.gpstore.dto.request.DeleteUploadRequest;
import com.gpstore.dto.request.SignBatchRequest;
import com.gpstore.dto.request.SignUploadRequest;
import com.gpstore.dto.response.CloudinarySignatureResponse;
import com.gpstore.dto.response.ConfirmedUploadBatchResponse;
import com.gpstore.dto.response.ConfirmedUploadResponse;
import com.gpstore.dto.response.R2ConnectionTestResponse;
import com.gpstore.dto.response.SignedUploadBatchResponse;
import com.gpstore.dto.response.SignedUploadResponse;
import com.gpstore.service.CloudinaryUploadService;
import com.gpstore.upload.R2ObjectStorageService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin-only image upload (SecurityConfig). Flutter never receives R2
 * secrets. {@code /cloudinary-signature} remains for already-installed
 * admin APKs until they are replaced.
 */
@RestController
@RequestMapping("/api/uploads")
public class UploadController {

    private final CloudinaryUploadService cloudinaryUploadService;
    private final R2ObjectStorageService r2;

    public UploadController(
            CloudinaryUploadService cloudinaryUploadService,
            R2ObjectStorageService r2) {
        this.cloudinaryUploadService = cloudinaryUploadService;
        this.r2 = r2;
    }

    @PostMapping("/sign")
    public SignedUploadResponse sign(@Valid @RequestBody SignUploadRequest request) {
        return r2.sign(request);
    }

    @PostMapping("/confirm")
    public ConfirmedUploadResponse confirm(@Valid @RequestBody ConfirmUploadRequest request) {
        return r2.confirm(request.getObjectKey());
    }

    @PostMapping("/sign-batch")
    public SignedUploadBatchResponse signBatch(@Valid @RequestBody SignBatchRequest request) {
        return new SignedUploadBatchResponse(r2.signBatch(request.getItems()));
    }

    @PostMapping("/confirm-batch")
    public ConfirmedUploadBatchResponse confirmBatch(@Valid @RequestBody ConfirmBatchRequest request) {
        return new ConfirmedUploadBatchResponse(r2.confirmBatch(request.getObjectKeys()));
    }

    /**
     * Puts a tiny JPEG, heads it, lists that key, then deletes it.
     * Admin-only. Never returns credentials.
     */
    @PostMapping("/r2-connection-test")
    public R2ConnectionTestResponse r2ConnectionTest() {
        return r2.connectionTest();
    }

    @PostMapping("/delete")
    public void delete(@Valid @RequestBody DeleteUploadRequest request) {
        r2.deletePublicUrl(request.getPublicUrl());
    }

    @GetMapping("/cloudinary-signature")
    public CloudinarySignatureResponse getCloudinarySignature() {
        return cloudinaryUploadService.generateSignedUploadParams();
    }
}
