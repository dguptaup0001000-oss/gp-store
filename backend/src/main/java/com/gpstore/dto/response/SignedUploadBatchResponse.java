package com.gpstore.dto.response;

import java.util.List;

public class SignedUploadBatchResponse {

    private final List<SignedUploadResponse> items;

    public SignedUploadBatchResponse(List<SignedUploadResponse> items) {
        this.items = items;
    }

    public List<SignedUploadResponse> getItems() {
        return items;
    }
}
