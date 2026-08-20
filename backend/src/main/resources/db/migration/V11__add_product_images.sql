-- Multi-image support for products.
--
-- A TABLE rather than image1..image5 columns: a fixed set of columns caps
-- the count forever, makes reordering a rewrite of every column, and leaves
-- holes when an image is removed from the middle. A row per image reorders
-- by touching one integer and grows past five if a product ever needs it.
--
-- DELIBERATELY NOT BACKFILLED. Existing products keep exactly what they
-- have: their single ProductVariant.imageUrl, untouched. The API falls back
-- to that variant image when a product has no rows here, so every existing
-- product keeps working with zero rows written by this migration and zero
-- risk of duplicating an image into a gallery. Images are added here only
-- when someone deliberately adds them.
--
-- product_id is the owner, not variant_id: the requirement is "a product has
-- 4-5 images". Variant images remain the per-variant thumbnail used in
-- listings, which is also why listings stay one small image per card rather
-- than pulling a gallery for every tile.
CREATE TABLE IF NOT EXISTS product_images (
    id          BIGSERIAL PRIMARY KEY,
    product_id  BIGINT       NOT NULL REFERENCES products (id) ON DELETE CASCADE,
    image_url   VARCHAR(500) NOT NULL,
    -- Explicit display order. Without it the gallery order would depend on
    -- whatever order Postgres happened to return, which changes over time
    -- and would silently reshuffle a product's images between requests.
    sort_order  INT          NOT NULL DEFAULT 0,
    created_at  TIMESTAMP    NOT NULL DEFAULT now()
);

-- Every read is "give me this product's images in order", so the index
-- covers both columns and the query needs no sort step.
CREATE INDEX IF NOT EXISTS idx_product_images_product_sort
    ON product_images (product_id, sort_order);

-- ON DELETE CASCADE above means deleting a product cleans up its images
-- rather than leaving orphans that no query will ever reach.
