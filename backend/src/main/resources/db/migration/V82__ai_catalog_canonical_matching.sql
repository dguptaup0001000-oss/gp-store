-- Safe canonical reuse for reviewed AI catalogue drafts.
-- A match points at the existing shared product/variant; shop-specific price,
-- stock, availability and commerce mode remain in shop-owned tables.

ALTER TABLE ai_catalog_drafts
    ADD COLUMN IF NOT EXISTS matched_product_id BIGINT REFERENCES products(id);
ALTER TABLE ai_catalog_drafts
    ADD COLUMN IF NOT EXISTS matched_variant_id BIGINT REFERENCES product_variants(id);

CREATE INDEX IF NOT EXISTS idx_ai_drafts_matched_variant
    ON ai_catalog_drafts (matched_variant_id)
    WHERE matched_variant_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_product_variants_barcode_nonempty
    ON product_variants (lower(barcode))
    WHERE barcode IS NOT NULL AND btrim(barcode) <> '';
