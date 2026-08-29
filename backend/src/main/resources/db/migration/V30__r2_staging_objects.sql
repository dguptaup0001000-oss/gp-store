-- Tracks R2 objects that were signed but not yet confirmed. The API token
-- must not have ListBucket, so the sweeper cannot list gpstore/staging/.
-- Rows older than 24h are deleted from R2 then removed here.

CREATE TABLE r2_staging_objects (
    object_key VARCHAR(512) PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_r2_staging_objects_created_at ON r2_staging_objects (created_at);
