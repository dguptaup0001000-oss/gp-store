-- V58: the billing architecture Part 4 asks for, with NO commercial amounts
-- in it anywhere.
--
-- WHAT THIS SUPERSEDES. A standing instruction said not to implement
-- commission arithmetic, platform commission fields or settlement
-- calculations "until the business model is explicitly finalized". Part 4 §5
-- changes that instruction in one specific way and not in others: the SHAPE
-- of the model is now given (§6: a weekly fee that acts as a credit against
-- commission, so a merchant pays max(P, C)), while the NUMBERS are still
-- explicitly not decided. So the arithmetic is built and the amounts are not:
-- there is not one rupee figure in this migration or in the code above it,
-- and with no plan configured no merchant is billed anything.
--
-- THREE TABLES, AND THE MIDDLE ONE IS THE POINT.
--
--   billing_plan   - what a tier costs, effective from a date. Configured by
--                    Platform Admin. NOT SEEDED: a default fee is a bill
--                    somebody did not agree to.
--   billing_period - a merchant's week. Opened, closed, settled.
--   merchant_ledger_entry
--                  - APPEND ONLY. Every fee, commission, credit, reversal,
--                    refund, recovery and adjustment as its own row.
--
-- WHY APPEND-ONLY AND NOT A BALANCE COLUMN. §9 says it outright - do not rely
-- on simple mutable totals without ledger history - and the reason is that a
-- balance is a claim you cannot check. When a merchant disputes a charge four
-- months later, "you owe 812" is unanswerable; a list of rows with dates,
-- reasons and the order each one came from is an answer. A correction here is
-- a NEW ROW pointing at the one it reverses, never an UPDATE, so the history
-- of what was charged and what was undone survives.

-- ---------------------------------------------- 1. what a tier costs, when
CREATE TABLE IF NOT EXISTS billing_plan (
    id              BIGSERIAL PRIMARY KEY,

    -- SMALL / MEDIUM / LARGE. A string rather than a database enum so a
    -- fourth tier is a row, not a migration on a live database.
    tier            VARCHAR(20)   NOT NULL,

    -- P in §6. Per week, in the smallest currency unit's decimal form.
    weekly_fee      NUMERIC(12,2) NOT NULL,

    -- C's rate in BASIS POINTS, not a float. 250 = 2.50%. Money rates held as
    -- doubles are money rates that disagree with themselves on the fourth
    -- decimal place, and a marketplace reconciles thousands of these a week.
    commission_bps  INTEGER       NOT NULL,

    -- Plans are VERSIONED BY DATE rather than edited. A merchant's invoice for
    -- a week in March has to be reproducible in October, and it cannot be if
    -- the plan row it was computed from has since been overwritten.
    effective_from  DATE          NOT NULL,
    effective_to    DATE,

    created_at      TIMESTAMP     NOT NULL DEFAULT NOW(),
    created_by      VARCHAR(120),
    note            VARCHAR(500),

    CONSTRAINT ck_billing_plan_tier CHECK (tier IN ('SMALL', 'MEDIUM', 'LARGE')),
    CONSTRAINT ck_billing_plan_fee  CHECK (weekly_fee >= 0),
    CONSTRAINT ck_billing_plan_bps  CHECK (commission_bps >= 0 AND commission_bps <= 10000),
    CONSTRAINT ck_billing_plan_dates CHECK (effective_to IS NULL OR effective_to > effective_from)
);

CREATE INDEX IF NOT EXISTS idx_billing_plan_tier_from ON billing_plan (tier, effective_from DESC);

-- Which tier a merchant is on. NULL = not assigned, and an unassigned
-- merchant is not billed at all - which is every merchant today.
ALTER TABLE merchants ADD COLUMN IF NOT EXISTS tier VARCHAR(20);

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_merchants_tier') THEN
        ALTER TABLE merchants ADD CONSTRAINT ck_merchants_tier
            CHECK (tier IS NULL OR tier IN ('SMALL', 'MEDIUM', 'LARGE'));
    END IF;
END $$;

-- ------------------------------------------------------- 2. a merchant week
CREATE TABLE IF NOT EXISTS billing_period (
    id          BIGSERIAL PRIMARY KEY,
    merchant_id BIGINT      NOT NULL,

    starts_on   DATE        NOT NULL,
    ends_on     DATE        NOT NULL,

    -- OPEN while the week is running, CLOSED once its entries are written,
    -- SETTLED once the money has actually moved. Three states because the
    -- middle one is where disputes live: a closed period has a number on it
    -- and has not been collected.
    status      VARCHAR(20) NOT NULL DEFAULT 'OPEN',

    closed_at   TIMESTAMP,
    settled_at  TIMESTAMP,
    created_at  TIMESTAMP   NOT NULL DEFAULT NOW(),

    CONSTRAINT ck_billing_period_status CHECK (status IN ('OPEN', 'CLOSED', 'SETTLED')),
    CONSTRAINT ck_billing_period_dates  CHECK (ends_on >= starts_on)
);

-- ONE PERIOD PER MERCHANT PER WEEK, enforced by the database rather than by
-- whoever runs the job. A weekly biller that runs twice - a retry, two
-- instances, a human - must not be able to bill the week twice, and this is
-- the constraint that makes that impossible rather than unlikely.
CREATE UNIQUE INDEX IF NOT EXISTS ux_billing_period_merchant_week
    ON billing_period (merchant_id, starts_on);
CREATE INDEX IF NOT EXISTS idx_billing_period_merchant ON billing_period (merchant_id, starts_on DESC);

-- ------------------------------------------------------- 3. the ledger
CREATE TABLE IF NOT EXISTS merchant_ledger_entry (
    id                BIGSERIAL PRIMARY KEY,
    merchant_id       BIGINT        NOT NULL,
    billing_period_id BIGINT,

    entry_type        VARCHAR(40)   NOT NULL,

    -- SIGNED, and the sign convention is stated once here so nothing has to
    -- guess: POSITIVE means the merchant owes GP-STORE, NEGATIVE means
    -- GP-STORE owes the merchant. A fee is positive, its refund is negative,
    -- a commission is positive, its reversal is negative.
    amount            NUMERIC(12,2) NOT NULL,
    currency          VARCHAR(3)    NOT NULL DEFAULT 'INR',

    -- WHY, as a code a machine can group by and a human can read. Free text
    -- alone makes "how much did we credit for no-order weeks" unanswerable.
    reason_code       VARCHAR(60)   NOT NULL,
    description       VARCHAR(500),

    -- The order this came from, where there is one. A commission row that
    -- cannot name its sale is a commission row nobody can check.
    order_id          BIGINT,

    -- A correction is a NEW ROW pointing at what it undoes, never an UPDATE.
    reversal_of_id    BIGINT,

    created_at        TIMESTAMP     NOT NULL DEFAULT NOW(),
    created_by        VARCHAR(120)  NOT NULL,

    CONSTRAINT ck_ledger_entry_type CHECK (entry_type IN (
        'PLATFORM_FEE',            -- P for the week
        'COMMISSION',              -- C on successful product sales
        'COMMISSION_CREDIT',       -- the fee acting as a credit (§6)
        'COMMISSION_REVERSAL',     -- a completed order was later refunded (§8)
        'FEE_REFUND_NO_ORDERS',    -- §7
        'INTERVENTION_RECOVERY',   -- GP-STORE refunded a customer, recovering it
        'ADJUSTMENT',              -- a human decision, with a reason
        'DISPUTE_HOLD',            -- contested, not collected
        'DISPUTE_RELEASE'
    ))
);

CREATE INDEX IF NOT EXISTS idx_ledger_merchant     ON merchant_ledger_entry (merchant_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_ledger_period       ON merchant_ledger_entry (billing_period_id);
CREATE INDEX IF NOT EXISTS idx_ledger_order        ON merchant_ledger_entry (order_id) WHERE order_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_ledger_reversal     ON merchant_ledger_entry (reversal_of_id) WHERE reversal_of_id IS NOT NULL;

-- ONE COMMISSION PER ORDER. A retried biller, a replayed event or a second
-- job instance must not charge a merchant twice for the same sale, and the
-- database is the only place that can promise it.
CREATE UNIQUE INDEX IF NOT EXISTS ux_ledger_one_commission_per_order
    ON merchant_ledger_entry (order_id)
    WHERE entry_type = 'COMMISSION' AND order_id IS NOT NULL;

-- ---------------------------------------------------- 4. APPEND ONLY, really
--
-- Enforced by the database, not by a code review. A ledger that can be
-- updated is a ledger whose history is a matter of trust - and the entire
-- reason §9 asks for one is to replace trust with a record.
CREATE OR REPLACE FUNCTION merchant_ledger_is_append_only() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'merchant_ledger_entry is append-only: % is not permitted. '
                    'Correct an entry by inserting a reversal that points at it.', TG_OP;
END $$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_merchant_ledger_append_only ON merchant_ledger_entry;
CREATE TRIGGER trg_merchant_ledger_append_only
    BEFORE UPDATE OR DELETE ON merchant_ledger_entry
    FOR EACH ROW EXECUTE FUNCTION merchant_ledger_is_append_only();

-- ------------------------------------------------------------------ VERIFY
DO $$
DECLARE
    n BIGINT;
BEGIN
    SELECT count(*) INTO n FROM billing_plan;
    IF n > 0 THEN
        RAISE EXCEPTION 'V58: % billing plan(s) were seeded. Part 4 §5 says the commercial '
                        'amounts are NOT decided; a default fee is a bill nobody agreed to.', n;
    END IF;

    SELECT count(*) INTO n FROM merchants WHERE tier IS NOT NULL;
    IF n > 0 THEN
        RAISE EXCEPTION 'V58: % merchant(s) were put on a tier by the migration', n;
    END IF;

    SELECT count(*) INTO n FROM merchant_ledger_entry;
    IF n > 0 THEN
        RAISE EXCEPTION 'V58: % ledger entries were seeded', n;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_trigger WHERE tgname = 'trg_merchant_ledger_append_only') THEN
        RAISE EXCEPTION 'V58: the ledger can be updated in place, which is the one thing §9 '
                        'says it must not be';
    END IF;
END $$;
