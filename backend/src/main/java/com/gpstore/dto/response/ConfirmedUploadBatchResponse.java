package com.gpstore.dto.response;

import java.util.List;

public class ConfirmedUploadBatchResponse {

    private final List<ConfirmedUploadResponse> items;

    public ConfirmedUploadBatchResponse(List<ConfirmedUploadResponse> items) {
        this.items = items;
    }

    public List<ConfirmedUploadResponse> getItems() {
        return items;
    }
}
