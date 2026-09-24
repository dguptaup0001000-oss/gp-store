-- Three ways to obtain what a shop has listed, on one column.
--
-- GP-STORE sells groceries online and now also wants to be where somebody
-- finds the jeweller who has the ring or the barber who does the haircut.
-- That is not three applications; it is the same marketplace with three
-- different endings, so it is one column on the row that already says "this
-- shop offers this thing at this price".
--
-- ON THE LISTING, NOT THE SHOP AND NOT THE PRODUCT. One phone shop sensibly
-- sells a charger online, asks you to come and look at a handset, and repairs
-- a screen on the bench; classifying the merchant would force them to pick one
-- and lie about the other two. And the same catalogue row can be shipped by
-- one shop and shown-only by another, so the mode is a fact about an offer
-- rather than about a thing.
--
-- NOTHING TRADE-SPECIFIC IS ADDED. No jewellery table, no service table, no
-- per-business columns. Purity, vehicle class and haircut type are all just
-- variant attributes, which this schema already carries generically for a
-- hundred business types. What actually differs between a ring and a haircut
-- is only how the sale ends, and that is these columns.

-- WRITTEN TO SURVIVE BOTH SCHEMA PATHS, which is not belt-and-braces here.
-- CI bootstraps by letting Hibernate create tables first and running Flyway
-- afterwards (the versioned scripts start at V2 and assume domain tables
-- exist). On that path Hibernate has already created these columns from the
-- entity, so ADD COLUMN IF NOT EXISTS is a no-op and any DEFAULT declared
-- inside it would never be applied. The defaults are therefore set in their
-- own statements, which run either way. This exact trap has bitten this
-- repository before.
ALTER TABLE shop_product_variants ADD COLUMN IF NOT EXISTS commerce_mode VARCHAR(24);
ALTER TABLE shop_product_variants ADD COLUMN IF NOT EXISTS price_mode VARCHAR(24);
ALTER TABLE shop_product_variants ADD COLUMN IF NOT EXISTS price_max NUMERIC(12, 2);
ALTER TABLE shop_product_variants ADD COLUMN IF NOT EXISTS offline_availability VARCHAR(24);
ALTER TABLE shop_product_variants ADD COLUMN IF NOT EXISTS service_duration_minutes INTEGER;

ALTER TABLE shop_product_variants ALTER COLUMN commerce_mode SET DEFAULT 'ONLINE_PURCHASE';
ALTER TABLE shop_product_variants ALTER COLUMN price_mode SET DEFAULT 'EXACT_PRICE';

-- EVERY EXISTING LISTING IS EXACTLY WHAT IT ALWAYS WAS. Not one row changes
-- meaning: a shelf that sold online yesterday sells online today. There is no
-- guesswork here and no trade-based reclassification - turning a merchant's
-- live products into display-only listings because a heuristic thought
-- jewellery belongs offline would take their shop off the internet without
-- asking them.
UPDATE shop_product_variants SET commerce_mode = 'ONLINE_PURCHASE' WHERE commerce_mode IS NULL;
UPDATE shop_product_variants SET price_mode = 'EXACT_PRICE' WHERE price_mode IS NULL;

ALTER TABLE shop_product_variants ALTER COLUMN commerce_mode SET NOT NULL;
ALTER TABLE shop_product_variants ALTER COLUMN price_mode SET NOT NULL;

-- A listing that says it is not sold online must not be able to claim a
-- committed price it will not honour, and one that IS sold online must have a
-- real number because a cart cannot total a range. The database says so
-- rather than trusting four callers to remember.
ALTER TABLE shop_product_variants DROP CONSTRAINT IF EXISTS ck_spv_online_price_is_exact;
ALTER TABLE shop_product_variants ADD CONSTRAINT ck_spv_online_price_is_exact
    CHECK (commerce_mode <> 'ONLINE_PURCHASE' OR price_mode = 'EXACT_PRICE');

ALTER TABLE shop_product_variants DROP CONSTRAINT IF EXISTS ck_spv_range_has_a_top;
ALTER TABLE shop_product_variants ADD CONSTRAINT ck_spv_range_has_a_top
    CHECK (price_mode <> 'PRICE_RANGE' OR price_max IS NOT NULL);

-- THE FEED FILTERS ON THIS COLUMN ON EVERY CUSTOMER REQUEST. Partial, because
-- the overwhelming majority of rows are and will remain ONLINE_PURCHASE, and
-- an index over one repeated value earns nothing; what needs finding quickly
-- is the minority that is not.
CREATE INDEX IF NOT EXISTS idx_spv_offline_modes
    ON shop_product_variants (shop_id, commerce_mode)
    WHERE commerce_mode <> 'ONLINE_PURCHASE';
