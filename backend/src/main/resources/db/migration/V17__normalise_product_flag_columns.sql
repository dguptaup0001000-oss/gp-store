-- Brings every environment's products table to the SAME shape.
--
-- THE BUG THIS CLOSES: admin "Add Product" failed in production with
--
--     null value in column "bestseller" of relation "products"
--     violates not-null constraint
--
-- and could not be reproduced anywhere else, because the schema was not the
-- same anywhere else.
--
-- V15 added these four columns as NOT NULL DEFAULT FALSE using
-- ADD COLUMN IF NOT EXISTS. That clause is why the environments diverged:
--
--   * Production ran V15 before the columns existed, so V15 created them and
--     they are NOT NULL there.
--   * On a developer machine and in CI, Hibernate's ddl-auto had already
--     created them from the entity - as plain nullable BOOLEAN - so
--     IF NOT EXISTS matched, V15 skipped them silently, and they stayed
--     nullable forever.
--
-- Same code, same migration history, two different schemas. Every local
-- reproduction of the admin failure succeeded, which is exactly what made it
-- look like a phantom.
--
-- Hibernate never omits a mapped column from an INSERT: it lists all of them
-- and binds NULL for anything unset, so DEFAULT FALSE never applies and the
-- NOT NULL constraint is what the row meets. Product now defaults these four
-- in Java (see Product.normaliseFlags) - this migration makes the database
-- agree everywhere, so a machine that cannot reproduce production is no
-- longer possible for these columns.
--
-- SAFE ON PRODUCTION, where it is a no-op: the columns are already NOT NULL
-- DEFAULT FALSE there, and SET NOT NULL on an already-NOT NULL column does
-- nothing. Safe to re-run.
--
-- The backfill runs first because SET NOT NULL is refused while any NULL
-- remains. FALSE is the honest value: these flags mean "has been marked as
-- bestseller / featured / test data / price-verified", and a row that was
-- never marked has not been.

UPDATE products
SET bestseller     = COALESCE(bestseller, FALSE),
    featured       = COALESCE(featured, FALSE),
    is_test_data   = COALESCE(is_test_data, FALSE),
    price_verified = COALESCE(price_verified, FALSE)
WHERE bestseller IS NULL
   OR featured IS NULL
   OR is_test_data IS NULL
   OR price_verified IS NULL;

ALTER TABLE products
    ALTER COLUMN bestseller     SET DEFAULT FALSE,
    ALTER COLUMN bestseller     SET NOT NULL,
    ALTER COLUMN featured       SET DEFAULT FALSE,
    ALTER COLUMN featured       SET NOT NULL,
    ALTER COLUMN is_test_data   SET DEFAULT FALSE,
    ALTER COLUMN is_test_data   SET NOT NULL,
    ALTER COLUMN price_verified SET DEFAULT FALSE,
    ALTER COLUMN price_verified SET NOT NULL;
