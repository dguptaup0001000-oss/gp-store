-- V54: a day the vans do not run belongs to ONE shop, not to the marketplace.
--
-- WHAT WAS WRONG. store_closures was the last piece of the "is this shop open"
-- answer still shared. Two things followed from that, and both are visible to
-- customers:
--
--   1. One merchant closing for a family wedding closed EVERY shop on the
--      marketplace. Nobody else agreed to shut, and nobody else could see why
--      their delivery window had moved.
--   2. ux_store_closures_closed_on made closed_on globally unique, so the
--      SECOND shop to declare Diwali was refused: "that day is already
--      closed" - by someone else's shop, which is not a thing a shopkeeper
--      can be told.
--
-- The second is the sharper failure of the two, because it means the shared
-- state could not even be worked around: on a marketplace, two shops closing
-- on the same festival is the normal case, not the edge case.
--
-- SAME SHAPE AS V49. The rows that exist are Shop #1's (V46 added the column
-- and backfilled it); the unique index moves from the date to the pair; the
-- entity becomes ShopOwned so the filter covers reads and the listener stamps
-- writes. No per-shop code, no second table. Shop N adds rows.

-- ------------------------------------------------- 1. the column, backfilled
--
-- V46 covered the tables that existed on its list; store_closures was not on
-- it, so the column is added here and filled the same way.
ALTER TABLE store_closures ADD COLUMN IF NOT EXISTS shop_id BIGINT;

DO $$
DECLARE
    v_shop_id BIGINT;
BEGIN
    SELECT id INTO v_shop_id FROM shops WHERE code = 'SHOP-1';
    IF v_shop_id IS NULL THEN
        RAISE EXCEPTION 'V54 cannot proceed: Shop #1 is missing';
    END IF;

    -- EVERY EXISTING CLOSURE IS SHOP #1'S, and that is a fact rather than a
    -- guess: until this migration there was only one shop that could have
    -- declared one.
    UPDATE store_closures SET shop_id = v_shop_id WHERE shop_id IS NULL;
END $$;

ALTER TABLE store_closures ALTER COLUMN shop_id SET NOT NULL;

-- --------------------------------------------- 2. the day is unique PER SHOP
--
-- BY SHAPE, NOT BY NAME. V33 created ux_store_closures_closed_on, and a
-- deployment that was ever bootstrapped with ddl-auto=update also carries a
-- Hibernate-generated one (uk<hash>) from the entity's @Column(unique = true).
-- Dropping only the name we know leaves the other in place, and the second
-- shop to declare a festival is still refused - by a constraint nobody can
-- find. So every unique constraint and index over exactly (closed_on) goes,
-- whatever it is called.
--
-- Dropped before the replacement is created, so there is no window in which a
-- shop could insert a duplicate day of its own.
DO $$
DECLARE
    v_attnum SMALLINT;
    r        RECORD;
BEGIN
    SELECT attnum INTO v_attnum FROM pg_attribute
    WHERE attrelid = 'store_closures'::regclass AND attname = 'closed_on' AND NOT attisdropped;

    FOR r IN
        SELECT con.conname AS name
        FROM pg_constraint con
        WHERE con.conrelid = 'store_closures'::regclass
          AND con.contype = 'u'
          AND con.conkey = ARRAY[v_attnum]
    LOOP
        EXECUTE format('ALTER TABLE store_closures DROP CONSTRAINT %I', r.name);
    END LOOP;

    FOR r IN
        SELECT cls.relname AS name
        FROM pg_index idx
        JOIN pg_class cls ON cls.oid = idx.indexrelid
        WHERE idx.indrelid = 'store_closures'::regclass
          AND idx.indisunique
          AND idx.indnatts = 1
          AND idx.indkey[0] = v_attnum
    LOOP
        EXECUTE format('DROP INDEX %I', r.name);
    END LOOP;
END $$;

CREATE UNIQUE INDEX IF NOT EXISTS ux_store_closures_shop_day
    ON store_closures (shop_id, closed_on);

CREATE INDEX IF NOT EXISTS idx_store_closures_shop_id ON store_closures (shop_id);

-- ------------------------------------------------- 3. no default, ever again
--
-- Same reasoning as V47: a shop_id default answers "which shop" without
-- anybody deciding. The entity listener stamps it from the scope instead.
ALTER TABLE store_closures ALTER COLUMN shop_id DROP DEFAULT;

-- ------------------------------------------------------------------ VERIFY
--
-- §92: a migration that ran is not a migration that was right.
DO $$
DECLARE
    bad BIGINT;
BEGIN
    SELECT count(*) INTO bad FROM store_closures WHERE shop_id IS NULL;
    IF bad > 0 THEN
        RAISE EXCEPTION 'V54: % closures still belong to no shop', bad;
    END IF;

    -- Asked by shape, for the same reason it was dropped by shape.
    SELECT count(*) INTO bad
    FROM pg_index idx
    WHERE idx.indrelid = 'store_closures'::regclass
      AND idx.indisunique
      AND idx.indnatts = 1
      AND idx.indkey[0] = (SELECT attnum FROM pg_attribute
                           WHERE attrelid = 'store_closures'::regclass
                             AND attname = 'closed_on' AND NOT attisdropped);
    IF bad > 0 THEN
        RAISE EXCEPTION 'V54: % marketplace-wide unique day(s) still in place, so the second '
                        'shop to close for a festival would still be refused', bad;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_indexes
                   WHERE schemaname = current_schema()
                     AND indexname = 'ux_store_closures_shop_day') THEN
        RAISE EXCEPTION 'V54: nothing stops one shop declaring the same day closed twice';
    END IF;

    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema = current_schema() AND table_name = 'store_closures'
                 AND column_name = 'shop_id' AND column_default IS NOT NULL) THEN
        RAISE EXCEPTION 'V54 failed to remove the shop_id default from store_closures';
    END IF;
END $$;
