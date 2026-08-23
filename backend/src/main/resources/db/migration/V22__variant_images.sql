-- Photos per VARIANT, not just per product.
--
-- WHY THIS COLUMN AND NOT A SECOND TABLE. V11 already built the right shape:
-- a row per image, with an explicit sort_order, owned by a product. The only
-- thing it cannot express is "these five photos are of the 1 kg pack, and
-- those five are of the 500 g pack" - which is what a shopkeeper photographing
-- a shelf actually produces. A parallel product_variant_images table would
-- duplicate every column, every index and every query in this file to say the
-- same thing about a different owner.
--
-- NULLABLE, AND THAT IS THE BACKWARD-COMPATIBILITY STORY IN ONE WORD. Every
-- row written before this migration keeps product_variant_id NULL and stays
-- exactly what it was: a product-level gallery image. Nothing is backfilled,
-- nothing is moved, no existing URL changes. A row with a variant belongs to
-- that variant; a row without belongs to the product as before. Both are
-- valid, both keep working, and the API reads them accordingly.
--
-- ProductVariant.imageUrl IS ALSO UNTOUCHED. It remains the single small
-- thumbnail every listing renders, which is what keeps a twenty-product grid
-- to twenty small images rather than a hundred. The variant gallery is read
-- on the detail screen and nowhere else - see ProductService.getProductById.
ALTER TABLE product_images
    ADD COLUMN IF NOT EXISTS product_variant_id BIGINT
        REFERENCES product_variants (id) ON DELETE CASCADE;

-- Every variant read is "give me this variant's images in order", so the
-- index covers both columns and the query needs no sort step - the same
-- reasoning as idx_product_images_product_sort in V11.
CREATE INDEX IF NOT EXISTS idx_product_images_variant_sort
    ON product_images (product_variant_id, sort_order)
    WHERE product_variant_id IS NOT NULL;

-- AT MOST FIVE PER VARIANT, enforced by the database and not only by the
-- service that writes them.
--
-- The limit is a product decision ("up to 5 photos per variant") and the
-- admin endpoint refuses a sixth. This is the backstop for everything that
-- does not go through that endpoint: a bulk import, a support script, a
-- future second writer. A count constraint cannot be expressed as a CHECK,
-- so it is a trigger - the only mechanism Postgres offers for this.
CREATE OR REPLACE FUNCTION enforce_variant_image_limit() RETURNS TRIGGER AS $$
BEGIN
    IF NEW.product_variant_id IS NOT NULL
       AND (SELECT COUNT(*) FROM product_images
            WHERE product_variant_id = NEW.product_variant_id) > 5 THEN
        RAISE EXCEPTION 'A product variant may have at most 5 images (variant %)',
            NEW.product_variant_id;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_variant_image_limit ON product_images;

-- AFTER, and FOR EACH STATEMENT deferred to row level via the count above:
-- a BEFORE trigger would not see the row being inserted, so a sixth image
-- would pass its own check. Counting after the insert is what makes the
-- limit actually five rather than six.
CREATE TRIGGER trg_variant_image_limit
    AFTER INSERT OR UPDATE OF product_variant_id ON product_images
    FOR EACH ROW EXECUTE FUNCTION enforce_variant_image_limit();
