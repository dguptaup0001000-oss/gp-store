package com.gpstore.controller;

import com.gpstore.dto.response.CloudinarySignatureResponse;
import com.gpstore.service.CloudinaryUploadService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/uploads")
public class UploadController {

    private final CloudinaryUploadService cloudinaryUploadService;

    public UploadController(CloudinaryUploadService cloudinaryUploadService) {
        this.cloudinaryUploadService = cloudinaryUploadService;
    }

    // Admin only (enforced in SecurityConfig) - hands the app just enough to
    // upload one image directly to Cloudinary itself; see
    // CloudinaryUploadService's doc comment for why the API secret never
    // leaves this endpoint.
    @GetMapping("/cloudinary-signature")
    public CloudinarySignatureResponse getCloudinarySignature() {
        return cloudinaryUploadService.generateSignedUploadParams();
    }
}
