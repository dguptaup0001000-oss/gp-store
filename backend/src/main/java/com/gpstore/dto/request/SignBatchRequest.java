package com.gpstore.dto.request;

import com.gpstore.upload.UploadPolicy;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public class SignBatchRequest {

    @NotEmpty
    @Size(max = UploadPolicy.BATCH_MAX)
    @Valid
    private List<SignUploadRequest> items;

    public List<SignUploadRequest> getItems() {
        return items;
    }

    public void setItems(List<SignUploadRequest> items) {
        this.items = items;
    }
}
