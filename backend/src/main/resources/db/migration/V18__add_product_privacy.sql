-- Private products: a presentation-and-recommendation privacy layer.
--
-- WHAT THIS IS. A product marked private keeps its real name here and
-- everywhere staff need it - inventory, fulfilment, accounting, refunds,
-- analytics, audit. What changes is what a CUSTOMER is shown about their own
-- past purchases, and whether the shop volunteers the product back to them in
-- a recommendation.
--
-- WHAT THIS IS NOT. It is not an age check, not a compliance control, and not
-- a way to make anything harder to find on purpose. Eligibility rules run
-- exactly where they already run; this sits after them and changes
-- presentation only. Nothing here removes or rewrites a real product name.
--
-- NOT NULL WITH A DEFAULT, AND DEFAULTED IN JAVA TOO. V17 exists because
-- Hibernate never omits a mapped column from an INSERT: it binds an explicit
-- NULL for anything unset, so a column DEFAULT never applies and the NOT NULL
-- is what the row meets. Product.isPrivateProduct therefore defaults to FALSE
-- in the entity as well. Setting only one of the two is how that bug happened.
--
-- Existing rows become non-private, which is the correct reading of a column
-- that did not exist: nothing was ever marked private.

-- ADD, THEN TIGHTEN - and the second half is not redundant.
--
-- V15 wrote "ADD COLUMN IF NOT EXISTS ... NOT NULL DEFAULT FALSE" and got a
-- nullable column on every machine where Hibernate's ddl-auto had already
-- created it from the entity: IF NOT EXISTS matched, and the whole clause -
-- constraint included - was skipped. Production and development ended up with
-- DIFFERENT schemas, and the bug that caused could not be reproduced locally
-- (see V17, which repaired it).
--
-- Since V19-era boot ordering runs Hibernate BEFORE Flyway
-- (FlywayAfterSchemaConfig), the column now always exists by the time this
-- runs, so ADD COLUMN alone would never apply the constraint anywhere. The
-- backfill and the explicit ALTERs are what actually make every environment
-- agree.
ALTER TABLE products
    ADD COLUMN IF NOT EXISTS is_private_product    BOOLEAN,
    ADD COLUMN IF NOT EXISTS customer_display_name VARCHAR(120);

UPDATE products SET is_private_product = FALSE WHERE is_private_product IS NULL;

ALTER TABLE products
    ALTER COLUMN is_private_product SET DEFAULT FALSE,
    ALTER COLUMN is_private_product SET NOT NULL;

-- Partial, like the other flag indexes in V15: private products are a small
-- minority, and every recommendation query filters on "= false", so what the
-- planner needs is a cheap way to find the few rows to EXCLUDE.
CREATE INDEX IF NOT EXISTS idx_products_is_private
    ON products (is_private_product) WHERE is_private_product = true;
