package com.gpstore.dto.request;

import com.gpstore.upload.UploadPolicy;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public class ConfirmBatchRequest {

    @NotEmpty
    @Size(max = UploadPolicy.BATCH_MAX)
    private List<@NotBlank String> objectKeys;

    public List<String> getObjectKeys() {
        return objectKeys;
    }

    public void setObjectKeys(List<String> objectKeys) {
        this.objectKeys = objectKeys;
    }
}
