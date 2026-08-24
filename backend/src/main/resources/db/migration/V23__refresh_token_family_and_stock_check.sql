-- Refresh-token families (reuse detection) and a database floor on stock.
--
-- Replaying a rotated refresh token must kill the live successor, not only
-- reject the replay. family_id groups the chain. Existing rows each get
-- their own family so a leftover hash cannot revoke an unrelated session.
--
-- inventory_stock_non_negative is defense in depth: checkout already locks
-- or uses an atomic UPDATE. This constraint makes a negative write fail
-- closed instead of persisting stock = -1.

ALTER TABLE refresh_tokens ADD COLUMN family_id VARCHAR(36);

UPDATE refresh_tokens
SET family_id = md5(random()::text || id::text)
WHERE family_id IS NULL;

CREATE INDEX idx_refresh_tokens_family_id ON refresh_tokens (family_id);

ALTER TABLE inventory
    ADD CONSTRAINT inventory_stock_non_negative CHECK (stock IS NULL OR stock >= 0);
