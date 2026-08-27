-- Instant-search ILIKE also probes search_keywords and subcategory.
-- V5 indexed name and brand; V27 tried to add these two GIN indexes with
-- the unqualified operator class gin_trgm_ops.
--
-- That name works on stock Postgres (CI: V5 CREATE EXTENSION pg_trgm into
-- public). Production is a Supabase dump: pg_trgm lives in schema
-- "extensions", so the operator class is extensions.gin_trgm_ops (see
-- idx_products_name_trgm / idx_products_brand_trgm in
-- production-schema-reference.sql). V27 failed with SQLState 42704
-- (operator class "gin_trgm_ops" does not exist for access method "gin")
-- and every deploy of that SHA rolled back.
--
-- V27 is left unchanged so its checksum stays valid once the failed history
-- row is marked success. This script is idempotent: CREATE INDEX IF NOT
-- EXISTS, and it picks extensions.gin_trgm_ops when that opclass exists,
-- otherwise gin_trgm_ops in whatever schema V5 used.
--
-- Do not CREATE EXTENSION pg_trgm here. V5 already does, and repeating it
-- would fail the deploy if the role is not allowed to create extensions.

DO $$
DECLARE
    opclass text;
BEGIN
    SELECT format('%I.%I', n.nspname, oc.opcname)
      INTO opclass
    FROM pg_opclass oc
    JOIN pg_am am ON am.oid = oc.opcmethod
    JOIN pg_namespace n ON n.oid = oc.opcnamespace
    WHERE oc.opcname = 'gin_trgm_ops'
      AND am.amname = 'gin'
    ORDER BY CASE n.nspname WHEN 'extensions' THEN 0 ELSE 1 END
    LIMIT 1;

    IF opclass IS NULL THEN
        RAISE NOTICE 'gin_trgm_ops not found; skipping search keyword trigram indexes';
        RETURN;
    END IF;

    EXECUTE format(
        'CREATE INDEX IF NOT EXISTS idx_products_search_keywords_trgm ON products USING GIN (search_keywords %s)',
        opclass);
    EXECUTE format(
        'CREATE INDEX IF NOT EXISTS idx_products_subcategory_trgm ON products USING GIN (subcategory %s)',
        opclass);
END $$;
