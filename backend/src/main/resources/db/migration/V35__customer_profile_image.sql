-- A customer's own avatar.
--
-- Stored as the same private-bucket reference the catalogue images use
-- (see CatalogImageRefs), not as a URL: signed URLs expire, so a persisted
-- one becomes a broken image within the hour.
--
-- Nullable with no default and no backfill. Every existing account genuinely
-- has no photo, and the app already draws an initial-letter avatar in that
-- case, so there is nothing to migrate to.
--
-- 512 rather than 255: a reference is a bucket path plus a UUID plus an
-- extension, and 255 leaves no headroom if the prefix ever changes.
ALTER TABLE customers
    ADD COLUMN IF NOT EXISTS profile_image_url VARCHAR(512);
