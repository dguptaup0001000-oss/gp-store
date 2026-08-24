-- Refresh-token families (reuse detection) and a database floor on stock.
--
-- CI's empty-database job boots Hibernate ddl-auto=update BEFORE Flyway, so
-- family_id may already exist from the entity. Production validate boots
-- Flyway first, so the column may not. ADD COLUMN IF NOT EXISTS is both.
--
-- inventory_stock_non_negative is defense in depth: checkout already locks
-- or uses an atomic UPDATE. This constraint makes a negative write fail
-- closed instead of persisting stock = -1.

ALTER TABLE refresh_tokens ADD COLUMN IF NOT EXISTS family_id VARCHAR(36);

UPDATE refresh_tokens
SET family_id = md5(random()::text || id::text)
WHERE family_id IS NULL;

CREATE INDEX IF NOT EXISTS idx_refresh_tokens_family_id ON refresh_tokens (family_id);

DO $$
BEGIN
    ALTER TABLE inventory
        ADD CONSTRAINT inventory_stock_non_negative CHECK (stock IS NULL OR stock >= 0);
EXCEPTION
    WHEN duplicate_object THEN NULL;
END $$;
