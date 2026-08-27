-- Instant-search ILIKE also probes search_keywords and subcategory.
-- V5 only indexed name and brand; a keyword that matches those extra
-- columns otherwise seq-scans the catalog. GIN trigram is the same
-- operator class already used on name/brand (~1,000 products today).
--
-- Do not CREATE EXTENSION pg_trgm here. V5 already does.

CREATE INDEX IF NOT EXISTS idx_products_search_keywords_trgm
    ON products USING GIN (search_keywords gin_trgm_ops);

CREATE INDEX IF NOT EXISTS idx_products_subcategory_trgm
    ON products USING GIN (subcategory gin_trgm_ops);
