-- Catalog metadata for the test-data seeding programme.
--
-- WHY EVERY COLUMN HERE IS NULLABLE WITH A DEFAULT: this table already holds
-- the shop's real products. A NOT NULL column without a default would fail
-- the migration outright on a non-empty table, and one with a default would
-- silently stamp every existing row with a value that is not true of it.
-- Existing products get NULL/false, which reads correctly as "not seeded,
-- not test data, never claimed to be verified".
--
-- SUBCATEGORY IS A COLUMN, NOT A SECOND CATEGORY ROW, and that is a
-- deliberate call rather than a shortcut. categories is flat - no parent_id -
-- and the home screen, the category rail and the Bestsellers tiles all list
-- categories directly. Introducing sixty child rows to that table would put
-- "Atta" and "Rice" on the home screen beside "Atta, Rice & Dal" and change
-- what every one of those surfaces renders. A column groups products within
-- a category without touching a single existing query.

ALTER TABLE products
    ADD COLUMN IF NOT EXISTS description     VARCHAR(1000),
    ADD COLUMN IF NOT EXISTS subcategory     VARCHAR(100),
    ADD COLUMN IF NOT EXISTS search_keywords VARCHAR(500),
    ADD COLUMN IF NOT EXISTS bestseller      BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS featured        BOOLEAN NOT NULL DEFAULT FALSE,

    -- THE PROVENANCE BLOCK. The whole point of the exercise: everything
    -- seeded is assumed data, and must stay findable as such after the
    -- session that created it is long forgotten.
    --
    -- is_test_data defaults FALSE so that anything already in the table -
    -- and anything a human adds through the admin screens later - is
    -- correctly NOT swept up by the pre-launch cleanup that keys on it.
    ADD COLUMN IF NOT EXISTS is_test_data    BOOLEAN NOT NULL DEFAULT FALSE,

    -- price_verified is separate from is_test_data on purpose: a product can
    -- stop being test data (someone confirms it is really stocked) long
    -- before anyone has checked its price against a shelf. Collapsing the two
    -- would let a half-verified product read as fully verified.
    ADD COLUMN IF NOT EXISTS price_verified  BOOLEAN NOT NULL DEFAULT FALSE,

    ADD COLUMN IF NOT EXISTS data_source     VARCHAR(60),
    ADD COLUMN IF NOT EXISTS image_source    VARCHAR(60),
    ADD COLUMN IF NOT EXISTS updated_at      TIMESTAMP;

-- The seeder's idempotency key. It upserts by variant SKU, so this index is
-- what makes "have I already seeded this product?" a lookup rather than a
-- scan over a thousand rows, once per row.
--
-- Partial, because SKU is nullable and every pre-existing variant has NULL:
-- a plain unique index would collapse them all into one conflicting key.
CREATE UNIQUE INDEX IF NOT EXISTS ux_product_variants_sku
    ON product_variants (sku) WHERE sku IS NOT NULL;

-- Supports the pre-launch cleanup and the admin "what still needs checking"
-- view. Partial again: the interesting rows are the seeded minority, and
-- indexing the FALSE majority would be dead weight on a 0.5 vCPU instance.
CREATE INDEX IF NOT EXISTS idx_products_is_test_data
    ON products (is_test_data) WHERE is_test_data = TRUE;

CREATE INDEX IF NOT EXISTS idx_products_subcategory
    ON products (subcategory) WHERE subcategory IS NOT NULL;

-- Bestseller and featured are read as "show me the flagged ones", which is a
-- small slice of the table.
CREATE INDEX IF NOT EXISTS idx_products_bestseller
    ON products (bestseller) WHERE bestseller = TRUE;

CREATE INDEX IF NOT EXISTS idx_products_featured
    ON products (featured) WHERE featured = TRUE;
