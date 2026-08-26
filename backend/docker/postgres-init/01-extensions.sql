-- Runs automatically the FIRST time the postgres container starts with an
-- empty data volume. Flyway V5 also creates pg_trgm and the product GIN
-- indexes after the products table exists. This init script only ensures
-- the extension is present for a superuser-owned Docker volume. No manual
-- CREATE INDEX step is required after first boot.
CREATE EXTENSION IF NOT EXISTS pg_trgm;
