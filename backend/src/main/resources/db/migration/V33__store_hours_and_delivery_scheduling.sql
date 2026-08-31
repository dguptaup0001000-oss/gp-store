-- 24/7 browsing with a 09:00-21:00 delivery window.
--
-- WHAT THIS ADDS: the owner's ON/OFF/AUTO switch, the days the vans do not
-- run, and two columns on orders recording when each order was scheduled for
-- and why. It changes NO existing column, drops nothing, and rewrites no row.
--
-- WHAT IT DELIBERATELY DOES NOT ADD: the hours themselves. 09:00 and 21:00
-- live in store.schedule.* configuration, not in a table, because they are a
-- deployment decision rather than something the shop edits between orders.
-- Putting them here would mean two sources for the same number.

-- ---------------------------------------------------------------------
-- The owner's switch. One row, id = 1, enforced.
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS store_operations_settings (
    id               BIGINT PRIMARY KEY,

    -- AUTO | ON | OFF. THREE STATES, NOT A BOOLEAN: a boolean cannot express
    -- "follow the schedule", so once somebody flips it off and on again the
    -- shop is pinned to whatever they last chose and the schedule stops being
    -- consulted. See StoreOrderAcceptance.
    order_acceptance VARCHAR(20)  NOT NULL DEFAULT 'AUTO',

    -- What customers are shown while orders are off, in the shop's own words.
    closure_message  VARCHAR(300),

    updated_at       TIMESTAMP,
    updated_by       VARCHAR(120)
);

-- Added individually as well, for a table an earlier ddl-auto may already have
-- created: CREATE TABLE IF NOT EXISTS on an existing table silently skips
-- every column it would have added.
ALTER TABLE store_operations_settings ADD COLUMN IF NOT EXISTS order_acceptance VARCHAR(20) NOT NULL DEFAULT 'AUTO';
ALTER TABLE store_operations_settings ADD COLUMN IF NOT EXISTS closure_message VARCHAR(300);
ALTER TABLE store_operations_settings ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP;
ALTER TABLE store_operations_settings ADD COLUMN IF NOT EXISTS updated_by VARCHAR(120);

-- One row only. A settings table with two rows silently applies whichever one
-- the query returned first.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'store_operations_settings_singleton'
    ) THEN
        ALTER TABLE store_operations_settings
            ADD CONSTRAINT store_operations_settings_singleton CHECK (id = 1);
    END IF;
END $$;

-- The column is @Enumerated(STRING), so Hibernate would generate its own CHECK
-- listing the values it knew about. Naming it here means adding a fourth state
-- later is a visible migration rather than a runtime constraint violation in
-- front of whoever pressed the button - the lesson of V32.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'store_operations_settings_acceptance_check'
    ) THEN
        ALTER TABLE store_operations_settings
            ADD CONSTRAINT store_operations_settings_acceptance_check
            CHECK (order_acceptance IN ('AUTO', 'ON', 'OFF'));
    END IF;
END $$;

-- The starting state: following the schedule, which means taking orders around
-- the clock. Inserted rather than defaulted in code so the shop can see the
-- switch from first boot instead of it materialising on first write.
INSERT INTO store_operations_settings (id, order_acceptance, updated_at, updated_by)
VALUES (1, 'AUTO', NOW(), 'V33 migration')
ON CONFLICT (id) DO NOTHING;

-- ---------------------------------------------------------------------
-- Days the vans do not run.
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS store_closures (
    id         BIGSERIAL PRIMARY KEY,

    -- A DATE, not a timestamp. "Closed on Holi" is a statement about a day in
    -- the shop's own calendar; stored as an instant it would begin and end at
    -- 05:30 local, which is the bug where a holiday starts at half past five
    -- in the morning.
    closed_on  DATE         NOT NULL,

    reason     VARCHAR(300),
    created_at TIMESTAMP    NOT NULL DEFAULT NOW(),
    created_by VARCHAR(120)
);

ALTER TABLE store_closures ADD COLUMN IF NOT EXISTS reason VARCHAR(300);
ALTER TABLE store_closures ADD COLUMN IF NOT EXISTS created_at TIMESTAMP NOT NULL DEFAULT NOW();
ALTER TABLE store_closures ADD COLUMN IF NOT EXISTS created_by VARCHAR(120);

-- The same day cannot be closed twice. Without this, two admins closing the
-- same festival produce two rows, and removing "the" closure removes one of
-- them and leaves the shop still shut with no visible reason why.
CREATE UNIQUE INDEX IF NOT EXISTS ux_store_closures_closed_on ON store_closures (closed_on);

-- NO SEED ROWS. A closure is a real day the shop is really shut; inventing
-- one so the table is not empty would cancel deliveries nobody asked to
-- cancel.

-- ---------------------------------------------------------------------
-- What each order was scheduled for.
-- ---------------------------------------------------------------------

-- Both NULLABLE, and left NULL for every existing order. Backfilling them
-- would invent a fact about a delivery that already happened - an order from
-- last March would be labelled SAME_DAY by a rule that did not exist then.
-- Null reads as "not recorded", which is true.
ALTER TABLE orders ADD COLUMN IF NOT EXISTS delivery_type VARCHAR(24);
ALTER TABLE orders ADD COLUMN IF NOT EXISTS scheduled_delivery_date DATE;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'orders_delivery_type_check'
    ) THEN
        ALTER TABLE orders
            ADD CONSTRAINT orders_delivery_type_check
            CHECK (delivery_type IS NULL
                   OR delivery_type IN ('SAME_DAY', 'NEXT_MORNING', 'MANUAL_SCHEDULED'));
    END IF;
END $$;

-- The morning preparation list is "orders for date D that still need packing",
-- and the analytics split is "orders by delivery type over a period". Both
-- filter on these columns, and without an index both become a sequential scan
-- of every order the shop has ever taken - which is exactly the "do not load
-- every order into memory" failure, just pushed down into Postgres.
CREATE INDEX IF NOT EXISTS idx_orders_scheduled_delivery_date
    ON orders (scheduled_delivery_date)
    WHERE scheduled_delivery_date IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_orders_delivery_type_date
    ON orders (delivery_type, order_date)
    WHERE delivery_type IS NOT NULL;
