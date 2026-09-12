-- V59: what it costs a customer to change their mind, decided by the shop.
--
-- §10 IS EXPLICIT THAT THE MERCHANT CONTROLS THIS, and equally explicit that
-- the customer must SEE the charge before confirming. Both halves are built
-- here: the numbers live per shop, and nothing takes money without having
-- quoted it first.
--
-- THE PLATFORM CAPS THE PERCENTAGE, it does not set it. §10 names 1-5% as the
-- current business concept, and a cap is the "platform's allowed mechanism"
-- it refers to - so the ceiling is configuration (platform.cancellation
-- .max-fee-percent) rather than a number in a column check, and a shop
-- charging 2% and one charging 4% are both correct.
--
-- FREE CANCELLATION IS A WINDOW, NOT A POLICY EXCEPTION. §9 says the existing
-- five-second countdown must remain, and the honest way to keep a promise
-- like that is to make it the shop's own setting with five as its default -
-- so it is visible, extendable by a shop that wants to be generous, and
-- enforced by the server rather than by whichever screen last drew a timer.

-- --------------------------------------- 1. the shop's cancellation terms
--
-- ON store_operations_settings rather than in a table of their own: that row
-- already is "how this shop operates" - one per shop, shop-owned, filtered
-- and stamped - and three columns do not earn a fourth table.
ALTER TABLE store_operations_settings
    ADD COLUMN IF NOT EXISTS free_cancellation_seconds INTEGER NOT NULL DEFAULT 5;

-- NULL means this shop charges nothing, which is the default and is different
-- from zero only in intent. Kept nullable so "we have not set a fee" and "we
-- have set it to nothing" are the same harmless answer.
ALTER TABLE store_operations_settings
    ADD COLUMN IF NOT EXISTS cancellation_fee_percent NUMERIC(5,2);

-- Whether the shop also recovers what it has already spent getting the packet
-- to the door. §10 allows it; it is off unless a shop turns it on.
ALTER TABLE store_operations_settings
    ADD COLUMN IF NOT EXISTS cancellation_charges_delivery BOOLEAN NOT NULL DEFAULT FALSE;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_store_ops_free_cancel') THEN
        ALTER TABLE store_operations_settings ADD CONSTRAINT ck_store_ops_free_cancel
            CHECK (free_cancellation_seconds >= 0 AND free_cancellation_seconds <= 3600);
    END IF;
    -- The database's own bound is deliberately WIDER than the platform's
    -- configured cap: the cap is policy and may change, this is a guard
    -- against a decimal point in the wrong place.
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_store_ops_cancel_fee') THEN
        ALTER TABLE store_operations_settings ADD CONSTRAINT ck_store_ops_cancel_fee
            CHECK (cancellation_fee_percent IS NULL
                   OR (cancellation_fee_percent >= 0 AND cancellation_fee_percent <= 100));
    END IF;
END $$;

-- ------------------------------------------ 2. what was actually charged
--
-- Recorded on the order, because a charge nobody can point at afterwards is a
-- charge that gets disputed. Null means none was applied - which is every
-- cancellation before this migration, and every one where the shop was at
-- fault (§12).
ALTER TABLE orders ADD COLUMN IF NOT EXISTS cancellation_fee NUMERIC(12,2);

-- ---------------------------------------------------- 3. COD leaves a debt
--
-- §11: a COD order collected nothing, so a cancellation charge cannot be
-- taken from a payment that never happened. It becomes something the customer
-- owes this shop, shown to them, and added to a later order.
--
-- SHOP-SCOPED, because the debt is to a shop and not to GP-STORE. A customer
-- who owes Shop A nothing should not be asked for it by Shop B, and a shop
-- must not be able to see what a customer owes its competitor.
CREATE TABLE IF NOT EXISTS customer_cancellation_dues (
    id               BIGSERIAL PRIMARY KEY,
    shop_id          BIGINT        NOT NULL,
    customer_id      BIGINT        NOT NULL,

    -- The cancellation that created the debt.
    order_id         BIGINT        NOT NULL,

    amount           NUMERIC(12,2) NOT NULL,

    -- OUTSTANDING until it is collected on a later order, or written off.
    status           VARCHAR(20)   NOT NULL DEFAULT 'OUTSTANDING',

    -- The order it was finally collected on.
    settled_order_id BIGINT,

    reason           VARCHAR(300),

    created_at       TIMESTAMP     NOT NULL DEFAULT NOW(),
    settled_at       TIMESTAMP,
    waived_by        VARCHAR(120),

    CONSTRAINT ck_dues_amount CHECK (amount > 0),
    CONSTRAINT ck_dues_status CHECK (status IN ('OUTSTANDING', 'SETTLED', 'WAIVED'))
);

-- ONE DEBT PER CANCELLED ORDER. A retried cancellation, or two requests
-- racing, must not bill the customer twice for changing their mind once.
CREATE UNIQUE INDEX IF NOT EXISTS ux_dues_per_order ON customer_cancellation_dues (order_id);
CREATE INDEX IF NOT EXISTS idx_dues_outstanding
    ON customer_cancellation_dues (shop_id, customer_id) WHERE status = 'OUTSTANDING';

-- ------------------------------------------------------------------ VERIFY
DO $$
DECLARE
    n BIGINT;
BEGIN
    SELECT count(*) INTO n FROM store_operations_settings
    WHERE cancellation_fee_percent IS NOT NULL;
    IF n > 0 THEN
        RAISE EXCEPTION 'V59: % shop(s) were given a cancellation fee by the migration. §10 '
                        'says the merchant sets it; a default fee is a charge nobody agreed to '
                        'and the customer would be the one paying it.', n;
    END IF;

    SELECT count(*) INTO n FROM store_operations_settings WHERE free_cancellation_seconds <> 5;
    IF n > 0 THEN
        RAISE EXCEPTION 'V59: the five-second free window §9 says must remain was not applied '
                        'to % shop(s)', n;
    END IF;

    SELECT count(*) INTO n FROM customer_cancellation_dues;
    IF n > 0 THEN
        RAISE EXCEPTION 'V59: % customer(s) were given a debt by the migration', n;
    END IF;
END $$;
