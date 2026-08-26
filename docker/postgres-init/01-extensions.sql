-- Runs automatically the FIRST time the postgres container starts with an
-- empty data volume. Flyway V5 creates pg_trgm again (IF NOT EXISTS) and
-- the product GIN indexes after the products table exists. No manual
-- CREATE INDEX step is required after first boot.
CREATE EXTENSION IF NOT EXISTS pg_trgm;
