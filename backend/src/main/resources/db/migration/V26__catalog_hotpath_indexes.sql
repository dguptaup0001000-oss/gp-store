-- Hot-path indexes for catalog browse and search on the Hostinger VPS.
--
-- idx_product_variants_sellable: findSellable / search EXISTS clauses filter
-- variants that can actually be sold. V2 already indexes product_id; this
-- partial index is the subset those queries actually probe.
--
-- idx_products_active_id: the home feed is `active = true ORDER BY id`.
--
-- Do not CREATE EXTENSION pg_trgm here. V5 already does, and repeating it
-- would fail the deploy if the role is not allowed to create extensions.
-- Instant search falls back to ILIKE when trigram operators are missing.

CREATE INDEX IF NOT EXISTS idx_product_variants_sellable
    ON product_variants (product_id)
    WHERE available = true
      AND selling_price IS NOT NULL
      AND selling_price > 0;

CREATE INDEX IF NOT EXISTS idx_products_active_id
    ON products (id)
    WHERE active = true;
