-- V55: a shop's trading hours become the SHOP'S, not the deployment's.
--
-- WHAT WAS WRONG. "When is this shop open" was answered by one
-- @ConfigurationProperties bean - store.schedule.delivery-start=09:00,
-- delivery-end=21:00, zone=Asia/Kolkata - shared by every shop on the
-- marketplace. A kirana that opens at seven and one that shuts at nine were
-- the same shop as far as the order path was concerned, and a merchant had
-- no way to say otherwise: the only per-shop controls were "taking orders /
-- not taking orders" and a list of days closed.
--
-- It is the single most obviously per-shop setting in the application, and
-- until now the one most obviously not.
--
-- TWO TABLES, TWO DIFFERENT QUESTIONS.
--
--   shop_business_hours - the ordinary week. One row per SESSION, so a shop
--     that shuts for lunch is two rows on that weekday rather than a second
--     column pair nobody would fill in. A weekday with no rows is a weekly
--     holiday, which is why the closed day needs no flag of its own.
--
--   shop_hours_override - "on the 14th we open late". Replaces that date's
--     weekday sessions. It is NOT the way to say "closed on the 14th" -
--     store_closures already says that, per shop since V54, with a reason and
--     an audit trail - so the two do not overlap and neither needs to know
--     about the other beyond a precedence rule (closed beats special hours).
--
-- NO SEED ROWS, AND THAT IS THE MIGRATION'S WHOLE SAFETY ARGUMENT (§19/§12).
-- A shop with no rows here has not configured its hours, and falls back to
-- the deployment configuration it has always run on. Shop #1 therefore keeps
-- trading at exactly the hours it traded at yesterday, and a SINGLE_SHOP
-- deployment never touches these tables at all. Backfilling 09:00-21:00 for
-- every shop would look identical today and would silently become wrong the
-- first time somebody edited the configuration file, because the rows would
-- no longer follow it.

-- ------------------------------------------------------------ the week
CREATE TABLE IF NOT EXISTS shop_business_hours (
    id          BIGSERIAL PRIMARY KEY,
    shop_id     BIGINT    NOT NULL,

    -- ISO-8601 numbering, 1 = Monday, so it matches java.time.DayOfWeek
    -- getValue() without a translation table nobody would keep in step.
    day_of_week SMALLINT  NOT NULL,

    opens_at    TIME      NOT NULL,
    closes_at   TIME      NOT NULL,

    created_at  TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP,

    CONSTRAINT ck_shop_hours_day   CHECK (day_of_week BETWEEN 1 AND 7),

    -- A SESSION ENDS AFTER IT STARTS. This also rules out an overnight
    -- session (18:00-02:00) rather than half-supporting one: every question
    -- below - is now inside a session, when does today's last run end, what
    -- date is this order for - would need a different answer if a session
    -- could span midnight, and a constraint that says so is better than four
    -- calculations that quietly disagree. A shop that trades past midnight is
    -- a real thing and a real piece of work; it is not this migration.
    CONSTRAINT ck_shop_hours_order CHECK (closes_at > opens_at)
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_shop_hours_session
    ON shop_business_hours (shop_id, day_of_week, opens_at);
CREATE INDEX IF NOT EXISTS idx_shop_hours_shop ON shop_business_hours (shop_id);

-- -------------------------------------------------- one date, different hours
CREATE TABLE IF NOT EXISTS shop_hours_override (
    id         BIGSERIAL PRIMARY KEY,
    shop_id    BIGINT    NOT NULL,
    on_date    DATE      NOT NULL,
    opens_at   TIME      NOT NULL,
    closes_at  TIME      NOT NULL,

    -- Shown to customers, so it is written for them.
    reason     VARCHAR(300),

    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    created_by VARCHAR(120),

    CONSTRAINT ck_shop_override_order CHECK (closes_at > opens_at)
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_shop_override_session
    ON shop_hours_override (shop_id, on_date, opens_at);
CREATE INDEX IF NOT EXISTS idx_shop_override_shop_date
    ON shop_hours_override (shop_id, on_date);

-- --------------------------------------------- "back in thirty minutes"
--
-- The acceptance override already had three states (AUTO / ON / OFF), and
-- OFF could only mean "off until somebody remembers to turn it back on".
-- A shopkeeper stepping out for half an hour had to choose between leaving
-- the shop open and taking orders they cannot pack, or closing it and
-- discovering at nine that evening that they never reopened.
--
-- paused_until is the missing half: OFF with a time is a pause that ends by
-- itself, OFF without one is closed until the shop says otherwise. No
-- scheduled job resumes it - the read path compares the clock, so a pause
-- that has run out is already over the next time anybody asks.
ALTER TABLE store_operations_settings ADD COLUMN IF NOT EXISTS paused_until TIMESTAMP;

-- ------------------------------------------------------------------ VERIFY
-- §92: a migration that ran is not a migration that was right.
DO $$
DECLARE
    n BIGINT;
BEGIN
    SELECT count(*) INTO n FROM shop_business_hours;
    IF n > 0 THEN
        RAISE EXCEPTION 'V55: % business-hour rows were seeded. Every shop must start on the '
                        'deployment configuration it has always traded on, or Shop #1 changes '
                        'its opening hours the day this deploys.', n;
    END IF;

    SELECT count(*) INTO n FROM shop_hours_override;
    IF n > 0 THEN
        RAISE EXCEPTION 'V55: % hour overrides were seeded', n;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_schema = current_schema()
                     AND table_name = 'store_operations_settings'
                     AND column_name = 'paused_until') THEN
        RAISE EXCEPTION 'V55: store_operations_settings has no paused_until, so a timed pause '
                        'cannot be recorded';
    END IF;

    -- The constraint is load-bearing (see its comment), so its absence is a
    -- failure rather than a detail.
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                   WHERE conname = 'ck_shop_hours_order'
                     AND conrelid = 'shop_business_hours'::regclass) THEN
        RAISE EXCEPTION 'V55: nothing stops a session that ends before it starts';
    END IF;
END $$;
