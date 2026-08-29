package com.gpstore.service;

import com.gpstore.dto.response.CloudinarySignatureResponse;
import com.gpstore.exception.ConflictException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Signs image uploads for Cloudinary's "signed upload" flow, so the Flutter
 * admin app can upload a product/variant image directly to Cloudinary -
 * this backend never receives or proxies the image bytes themselves, only
 * this one small signature (see the reality-check note on why that matters:
 * this app was already not in the image-serving path before Cloudinary was
 * wired up - a raw text "Image URL" field the admin pasted into. This
 * replaces that manual step with a real upload, without changing that
 * property).
 *
 * The API secret this signs with never leaves the server - only the
 * resulting signature does. Cloudinary verifies the upload request by
 * recomputing the same signature server-side on their end.
 */
@Service
public class CloudinaryUploadService {

    private final String cloudName;
    private final String apiKey;
    private final String apiSecret;
    private final String uploadFolder;
    private final boolean signatureEnabled;

    CloudinaryUploadService(String cloudName, String apiKey, String apiSecret, String uploadFolder) {
        this(cloudName, apiKey, apiSecret, uploadFolder, false);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public CloudinaryUploadService(
            @Value("${cloudinary.cloud-name:}") String cloudName,
            @Value("${cloudinary.api-key:}") String apiKey,
            @Value("${cloudinary.api-secret:}") String apiSecret,
            @Value("${cloudinary.upload-folder:gp-store/products}") String uploadFolder,
            @Value("${cloudinary.signature-enabled:false}") boolean signatureEnabled) {
        this.cloudName = cloudName;
        this.apiKey = apiKey;
        this.apiSecret = apiSecret;
        this.uploadFolder = uploadFolder;
        this.signatureEnabled = signatureEnabled;
    }

    public CloudinarySignatureResponse generateSignedUploadParams() {
        if (!signatureEnabled) {
            throw new ConflictException(
                    "Cloudinary signed upload is turned off. New photos use R2.");
        }
        if (cloudName.isBlank() || apiKey.isBlank() || apiSecret.isBlank()) {
            throw new ConflictException(
                    "Image upload isn't configured yet - set CLOUDINARY_CLOUD_NAME, CLOUDINARY_API_KEY, "
                            + "and CLOUDINARY_API_SECRET (see backend/DEPLOYMENT.md).");
        }

        long timestamp = System.currentTimeMillis() / 1000;

        // Cloudinary's signature: every param that will be sent EXCEPT file,
        // cloud_name, api_key, and resource_type, sorted alphabetically by
        // key as "key=value" pairs joined with "&", with the API secret
        // appended directly (no separator) - then SHA-1 the whole string.
        // "folder" sorts before "timestamp" alphabetically, which is also
        // the order they're written below - not a coincidence, this only
        // works if the string matches Cloudinary's own sort exactly.
        String paramsToSign = "folder=" + uploadFolder + "&timestamp=" + timestamp;
        String signature = sha1Hex(paramsToSign + apiSecret);

        return new CloudinarySignatureResponse(cloudName, apiKey, timestamp, signature, uploadFolder);
    }

    private String sha1Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            // SHA-1 is a JDK-guaranteed algorithm (every conforming JVM
            // implementation must provide it) - this can never actually
            // happen, but the checked exception still has to go somewhere.
            throw new IllegalStateException("SHA-1 unavailable", ex);
        }
    }
}
