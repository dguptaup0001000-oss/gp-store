-- Hot-path indexes that V2/V5 did not cover.
--
-- GET /api/categories is `WHERE active ORDER BY name LIMIT 100`. V2 never
-- indexed categories. Leftover test rows (thousands of active categories)
-- made that a sequential scan + sort on every cache miss.
--
-- Instant search ORs `search_keywords ILIKE` and `subcategory ILIKE` after
-- the name/brand trigram match. V5 indexed name and brand only, so those
-- two OR branches still seq-scanned products.
--
-- carts.customer_id is already unique (Hibernate @OneToOne) - do not add
-- a second index there.
--
-- CREATE INDEX IF NOT EXISTS, not CONCURRENTLY: same reasoning as V2.

CREATE INDEX IF NOT EXISTS idx_categories_active_name
    ON categories (active, name);

CREATE INDEX IF NOT EXISTS idx_products_search_keywords_trgm
    ON products USING GIN (search_keywords gin_trgm_ops);

CREATE INDEX IF NOT EXISTS idx_products_subcategory_trgm
    ON products USING GIN (subcategory gin_trgm_ops);
