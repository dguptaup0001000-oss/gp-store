-- The fifteen characters a merchant types once, to claim their account.
--
-- WHAT IS STORED IS NOT THE CODE. activation_code_hash holds a SHA-256
-- fingerprint; the code itself is shown once, at generation, and exists
-- nowhere afterwards. There is deliberately no route that returns it, so a
-- lost code is reissued (§30) rather than recovered.
--
-- WHY THE HASH CARRIES THE UNIQUE INDEX. §23 requires the code to be unique,
-- and uniqueness has to be ENFORCED rather than hoped for. A fingerprint
-- column is one a database can index; a bcrypt column, with its per-row salt,
-- is not. The secret behind it has about 87 bits of entropy, so a fast hash
-- costs nothing here - see ActivationCodes for the full reasoning, written
-- down so the index is not quietly lost to a later "upgrade".
--
-- CLAIMED, NOT DELETED. activation_code_claimed_at is what makes the code
-- one-time: a claimed code stops authenticating, and the row stays so the
-- audit trail can still say the account was claimed and when.
--
-- NOTHING IS BACKFILLED. Every column is nullable and every existing account
-- keeps signing in exactly as it does today: a NULL hash means "this account
-- has no activation code", which is the correct description of every account
-- that existed before this migration, including Shop #1's owner.

ALTER TABLE customers ADD COLUMN IF NOT EXISTS activation_code_hash VARCHAR(64);
ALTER TABLE customers ADD COLUMN IF NOT EXISTS activation_code_issued_at TIMESTAMP;
ALTER TABLE customers ADD COLUMN IF NOT EXISTS activation_code_claimed_at TIMESTAMP;

-- PARTIAL, so the many accounts with no code do not collide with each other
-- on NULL - and so a reissue can free the old value.
CREATE UNIQUE INDEX IF NOT EXISTS uk_customers_activation_code
    ON customers (activation_code_hash)
    WHERE activation_code_hash IS NOT NULL;
