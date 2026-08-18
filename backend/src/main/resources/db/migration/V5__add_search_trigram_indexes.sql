-- Converts the previously-manual pg_trgm setup (see README-search.md) into a
-- real migration, so it actually runs instead of depending on someone
-- remembering to paste it into a SQL console by hand.
--
-- Root-caused as the likely cause of ProductRepository.searchInstant()'s
-- ~6s median latency under the Phase 2 load test: CREATE EXTENSION pg_trgm
-- was clearly run at some point already (search returns correct results
-- instead of hard-failing with "undefined function similarity"), but these
-- two indexes were not - without them, both the "p.name % :keyword"
-- trigram match AND the "ORDER BY similarity(...)" ranking fall back to a
-- full sequential scan over every active product, computed twice per
-- request (once for the data query, once for Page's separate count query).
--
-- CREATE INDEX IF NOT EXISTS (not CONCURRENTLY, same reasoning as V2): safe
-- and fast at this store's current catalog size. Revisit with CONCURRENTLY
-- (run outside Flyway's transactional migration) if the catalog grows large
-- enough that a brief write-lock during index creation becomes a concern.
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX IF NOT EXISTS idx_products_name_trgm ON products USING GIN (name gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_products_brand_trgm ON products USING GIN (brand gin_trgm_ops);
