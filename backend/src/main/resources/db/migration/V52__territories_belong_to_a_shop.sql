-- V52: a territory belongs to the shop that drew it, and so does the rider who works it.
--
-- DECISION W4, TAKEN: riders are PER SHOP. There is no shared pool. Each shop
-- has its own delivery workers, and a worker belongs to exactly one shop unless
-- a platform-level delivery service is deliberately introduced later. This
-- migration is what makes the geography agree with that.
--
-- WHY THE MAP CANNOT STAY PLATFORM-LEVEL. Two kiranas 400 metres apart serve
-- overlapping ground and divide it completely differently: one thinks in terms
-- of the four colonies its riders know, the other in terms of the market road
-- and everything off it. A single shared map forces both to accept one
-- shopkeeper's idea of where the boundaries are, and - worse - hands whichever
-- shop resolves an address first the right to decide which rider a competitor's
-- order goes to. A territory is a working arrangement between one shop and its
-- own riders. It belongs to that shop.
--
-- CODES BECOME UNIQUE PER SHOP, NOT GLOBALLY. Every kirana that draws a map
-- will call its first zone Z1, and both are right. A global unique index on
-- code would mean the second merchant onto the platform cannot name their own
-- territories, which is not a constraint anybody chose - it is a leftover from
-- there being one shop.
--
-- WHAT DOES NOT CHANGE. addresses.subzone_id stays exactly as it is, and under
-- SINGLE_SHOP everything about the territory system behaves as it did before
-- this migration: one shop, one map, the same 8 zones and 26 subzones with the
-- same codes and the same riders.

-- ------------------------------------------------------------------- COLUMNS
ALTER TABLE delivery_zones           ADD COLUMN IF NOT EXISTS shop_id BIGINT REFERENCES shops (id);
ALTER TABLE delivery_subzones        ADD COLUMN IF NOT EXISTS shop_id BIGINT REFERENCES shops (id);
ALTER TABLE subzone_backup_partners  ADD COLUMN IF NOT EXISTS shop_id BIGINT REFERENCES shops (id);

CREATE INDEX IF NOT EXISTS idx_delivery_zones_shop          ON delivery_zones (shop_id);
CREATE INDEX IF NOT EXISTS idx_delivery_subzones_shop       ON delivery_subzones (shop_id);
CREATE INDEX IF NOT EXISTS idx_subzone_backup_partners_shop ON subzone_backup_partners (shop_id);

-- ------------------------------------------------------------------ BACKFILL
--
-- Everything drawn so far was drawn by the one shop that exists. §2: the
-- current shop becomes Shop #1 and keeps working.
DO $$
DECLARE
    v_shop_id  BIGINT;
    v_zones    BIGINT;
    v_subzones BIGINT;
    v_backups  BIGINT;
BEGIN
    SELECT id INTO v_shop_id FROM shops ORDER BY id LIMIT 1;
    IF v_shop_id IS NULL THEN
        RAISE EXCEPTION 'V52 cannot run before a first shop exists (V46 creates it)';
    END IF;

    UPDATE delivery_zones          SET shop_id = v_shop_id WHERE shop_id IS NULL;
    GET DIAGNOSTICS v_zones = ROW_COUNT;
    UPDATE delivery_subzones       SET shop_id = v_shop_id WHERE shop_id IS NULL;
    GET DIAGNOSTICS v_subzones = ROW_COUNT;
    UPDATE subzone_backup_partners SET shop_id = v_shop_id WHERE shop_id IS NULL;
    GET DIAGNOSTICS v_backups = ROW_COUNT;

    RAISE NOTICE 'V52: % zone(s), % subzone(s) and % backup rider row(s) now belong to Shop #1',
        v_zones, v_subzones, v_backups;
END $$;

-- ----------------------------------------------------------------- UNIQUENESS
--
-- Dropped and re-made per shop. The old indexes said "no two territories
-- anywhere may share a code", which was a true statement about a single shop
-- and is a false one about a marketplace.
DROP INDEX IF EXISTS uq_delivery_zones_code;
DROP INDEX IF EXISTS uq_delivery_subzones_code;
ALTER TABLE delivery_zones    DROP CONSTRAINT IF EXISTS uq_delivery_zones_code;
ALTER TABLE delivery_subzones DROP CONSTRAINT IF EXISTS uq_delivery_subzones_code;

CREATE UNIQUE INDEX IF NOT EXISTS uq_delivery_zones_shop_code
    ON delivery_zones (shop_id, code);
CREATE UNIQUE INDEX IF NOT EXISTS uq_delivery_subzones_shop_code
    ON delivery_subzones (shop_id, code);

-- ------------------------------------------------------------------ VERIFY
--
-- §92: a migration that ran is not a migration that worked. Each check below
-- has been proved to fire by breaking a row on purpose.
DO $$
DECLARE
    orphan_zones     BIGINT;
    orphan_subzones  BIGINT;
    orphan_backups   BIGINT;
    crossed_subzones BIGINT;
    crossed_riders   BIGINT;
    crossed_backups  BIGINT;
BEGIN
    SELECT count(*) INTO orphan_zones    FROM delivery_zones          WHERE shop_id IS NULL;
    SELECT count(*) INTO orphan_subzones FROM delivery_subzones       WHERE shop_id IS NULL;
    SELECT count(*) INTO orphan_backups  FROM subzone_backup_partners WHERE shop_id IS NULL;

    IF orphan_zones > 0 OR orphan_subzones > 0 OR orphan_backups > 0 THEN
        RAISE EXCEPTION 'V52 left % zone(s), % subzone(s) and % backup row(s) belonging to no shop',
            orphan_zones, orphan_subzones, orphan_backups;
    END IF;

    -- A subzone in another shop's zone is a map with a hole in it: the shop
    -- that owns the subzone cannot see the zone it hangs off.
    SELECT count(*) INTO crossed_subzones
    FROM delivery_subzones s JOIN delivery_zones z ON z.id = s.zone_id
    WHERE s.shop_id IS DISTINCT FROM z.shop_id;

    IF crossed_subzones > 0 THEN
        RAISE EXCEPTION 'V52: % subzone(s) sit inside another shop''s zone', crossed_subzones;
    END IF;

    -- W4: a territory's rider must work for the shop that drew the territory.
    -- One of these is the whole point of the slice - a rider assigned across a
    -- shop boundary is one merchant dispatching another merchant's staff.
    SELECT count(*) INTO crossed_riders
    FROM delivery_subzones s JOIN delivery_partners p ON p.id = s.primary_partner_id
    WHERE s.shop_id IS DISTINCT FROM p.shop_id;

    SELECT count(*) INTO crossed_backups
    FROM subzone_backup_partners b JOIN delivery_partners p ON p.id = b.partner_id
    WHERE b.shop_id IS DISTINCT FROM p.shop_id;

    IF crossed_riders > 0 OR crossed_backups > 0 THEN
        RAISE EXCEPTION 'V52: % territory rider(s) and % backup rider(s) belong to a different '
                        'shop than the territory they work', crossed_riders, crossed_backups;
    END IF;
END $$;
