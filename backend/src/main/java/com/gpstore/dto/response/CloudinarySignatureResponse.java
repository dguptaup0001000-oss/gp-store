package com.gpstore.dto.response;

/**
 * Everything the Flutter admin app needs to upload an image directly to
 * Cloudinary itself (see UploadController) - the API secret used to
 * produce `signature` never leaves the backend, only its result does.
 */
public class CloudinarySignatureResponse {

    private final String cloudName;
    private final String apiKey;
    private final long timestamp;
    private final String signature;
    private final String folder;

    public CloudinarySignatureResponse(String cloudName, String apiKey, long timestamp, String signature, String folder) {
        this.cloudName = cloudName;
        this.apiKey = apiKey;
        this.timestamp = timestamp;
        this.signature = signature;
        this.folder = folder;
    }

    public String getCloudName() { return cloudName; }
    public String getApiKey() { return apiKey; }
    public long getTimestamp() { return timestamp; }
    public String getSignature() { return signature; }
    public String getFolder() { return folder; }
}
