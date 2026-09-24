-- V74 backfilled every existing listing and made commerce_mode NOT NULL.
-- Removing the column default makes any future writer that omits the mode
-- fail instead of silently publishing that listing as Buy Online.
ALTER TABLE shop_product_variants ALTER COLUMN commerce_mode DROP DEFAULT;
