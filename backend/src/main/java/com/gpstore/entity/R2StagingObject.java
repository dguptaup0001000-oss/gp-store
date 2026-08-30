package com.gpstore.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "r2_staging_objects")
public class R2StagingObject {

    @Id
    @Column(name = "object_key", nullable = false, length = 512)
    private String objectKey;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected R2StagingObject() {}

    public R2StagingObject(String objectKey, Instant createdAt) {
        this.objectKey = objectKey;
        this.createdAt = createdAt;
    }

    public String getObjectKey() {
        return objectKey;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
