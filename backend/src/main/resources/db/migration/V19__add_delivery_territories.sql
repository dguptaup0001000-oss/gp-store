-- Permanent delivery territories: 8 main zones -> 26 subzones -> partners.
--
-- WHAT IS PERMANENT AND WHAT IS NOT. This migration creates only the
-- permanent half of the territory system: the geography, the partner who
-- owns each territory, and the neighbours each territory may borrow from.
-- None of it changes when order volume changes. The dynamic half - who is
-- available right now, how many active orders each partner is carrying,
-- which order got handed to a backup - lives in delivery_partners and
-- deliveries, which already exist, plus the two assignment columns added at
-- the bottom of this file.
--
-- WHY POLYGONS AND NOT RADII. A radius cannot express "the far side of the
-- railway line", and that is exactly the distinction a delivery rider cares
-- about. Two houses 200 m apart with no bridge between them are an hour
-- apart on a scooter.
--
-- WHY NOT PostGIS. CI and production run the plain postgres image, which has no PostGIS,
-- and enabling it on Supabase is a change to someone's production database.
-- At this scale - 26 polygons of a few dozen vertices - a ray-casting test in
-- Java costs microseconds and needs no extension, no new CI image, and no
-- spatial index. See TerritoryResolver. The boundary is stored as JSON text so
-- that moving to PostGIS later is a data migration, not a redesign.
--
-- THE NOT NULL DANCE. Hibernate runs before Flyway in this application (see
-- FlywayAfterSchemaConfig), so ddl-auto has usually created these columns as
-- nullable by the time this file runs. "ADD COLUMN IF NOT EXISTS ... NOT NULL"
-- silently does nothing when the column already exists, which is how V15
-- shipped a nullable column that production then broke on. Every constraint
-- below is therefore applied with an explicit ALTER after a backfill.

-- ---------------------------------------------------------------------------
-- 8 main zones
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS delivery_zones (
    id            BIGSERIAL PRIMARY KEY,
    code          VARCHAR(16)  NOT NULL,
    name          VARCHAR(120) NOT NULL,
    -- Free text for the human who drew it: "north of the canal, everything
    -- the bridge at Station Road reaches". Why a boundary sits where it does
    -- is the single most useful thing to record and the first thing lost.
    notes         TEXT,
    display_order INTEGER,
    active        BOOLEAN
);

ALTER TABLE delivery_zones ADD COLUMN IF NOT EXISTS code VARCHAR(16);
ALTER TABLE delivery_zones ADD COLUMN IF NOT EXISTS name VARCHAR(120);
ALTER TABLE delivery_zones ADD COLUMN IF NOT EXISTS notes TEXT;
ALTER TABLE delivery_zones ADD COLUMN IF NOT EXISTS display_order INTEGER;
ALTER TABLE delivery_zones ADD COLUMN IF NOT EXISTS active BOOLEAN;

UPDATE delivery_zones SET active = TRUE WHERE active IS NULL;
ALTER TABLE delivery_zones ALTER COLUMN active SET DEFAULT TRUE;
ALTER TABLE delivery_zones ALTER COLUMN active SET NOT NULL;

-- One Z7, ever. Codes are what partners and dispatchers say out loud.
CREATE UNIQUE INDEX IF NOT EXISTS uq_delivery_zones_code ON delivery_zones (code);

-- ---------------------------------------------------------------------------
-- 26 permanent subzones
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS delivery_subzones (
    id                    BIGSERIAL PRIMARY KEY,
    zone_id               BIGINT       NOT NULL,
    code                  VARCHAR(16)  NOT NULL,
    name                  VARCHAR(120) NOT NULL,
    boundary              TEXT,
    primary_partner_id    BIGINT,
    max_concurrent_orders INTEGER,
    notes                 TEXT,
    display_order         INTEGER,
    active                BOOLEAN
);

ALTER TABLE delivery_subzones ADD COLUMN IF NOT EXISTS zone_id BIGINT;
ALTER TABLE delivery_subzones ADD COLUMN IF NOT EXISTS code VARCHAR(16);
ALTER TABLE delivery_subzones ADD COLUMN IF NOT EXISTS name VARCHAR(120);
-- TEXT, not JSONB: Hibernate binds a String attribute as varchar and Postgres
-- will not implicitly cast that to jsonb - every insert failed with "column
-- boundary is of type jsonb but expression is of type character varying". The
-- validation jsonb would buy is also weaker than what TerritoryAdminService
-- already does before every write: well-formed JSON that is not a ring of at
-- least three coordinate pairs satisfies jsonb and is still useless.
ALTER TABLE delivery_subzones ADD COLUMN IF NOT EXISTS boundary TEXT;
ALTER TABLE delivery_subzones ADD COLUMN IF NOT EXISTS primary_partner_id BIGINT;
ALTER TABLE delivery_subzones ADD COLUMN IF NOT EXISTS max_concurrent_orders INTEGER;
ALTER TABLE delivery_subzones ADD COLUMN IF NOT EXISTS notes TEXT;
ALTER TABLE delivery_subzones ADD COLUMN IF NOT EXISTS display_order INTEGER;
ALTER TABLE delivery_subzones ADD COLUMN IF NOT EXISTS active BOOLEAN;

UPDATE delivery_subzones SET active = TRUE WHERE active IS NULL;
ALTER TABLE delivery_subzones ALTER COLUMN active SET DEFAULT TRUE;
ALTER TABLE delivery_subzones ALTER COLUMN active SET NOT NULL;

-- The capacity above which the primary partner is considered overloaded and
-- the overflow ladder starts looking for help. Deliberately per-subzone: a
-- dense colony of walk-up flats and a spread-out set of farm houses do not
-- have the same "reasonable" number, and forcing one number on both is the
-- equal-workload assumption this whole design rejects.
UPDATE delivery_subzones SET max_concurrent_orders = 12 WHERE max_concurrent_orders IS NULL;
ALTER TABLE delivery_subzones ALTER COLUMN max_concurrent_orders SET DEFAULT 12;
ALTER TABLE delivery_subzones ALTER COLUMN max_concurrent_orders SET NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_delivery_subzones_code ON delivery_subzones (code);
CREATE INDEX IF NOT EXISTS idx_delivery_subzones_zone ON delivery_subzones (zone_id);
CREATE INDEX IF NOT EXISTS idx_delivery_subzones_primary ON delivery_subzones (primary_partner_id);

-- ---------------------------------------------------------------------------
-- Which subzones may lend a partner to which - DECLARED, never derived
-- ---------------------------------------------------------------------------
-- Two polygons sharing an edge are not necessarily neighbours for delivery.
-- If a six-lane highway runs down that shared edge with no crossing for two
-- kilometres, a rider in one cannot reach the other quickly, and a dispatcher
-- that "helpfully" infers adjacency from geometry will hand out exactly the
-- assignment this system exists to prevent. Adjacency is therefore a
-- deliberate statement by whoever knows the roads, stored here.
--
-- Stored as an undirected edge written in both directions, so a lookup is a
-- single indexed read rather than an OR across two columns.
CREATE TABLE IF NOT EXISTS subzone_neighbours (
    subzone_id          BIGINT NOT NULL,
    neighbour_subzone_id BIGINT NOT NULL,
    PRIMARY KEY (subzone_id, neighbour_subzone_id)
);

-- ---------------------------------------------------------------------------
-- Named standing backups per subzone, in priority order
-- ---------------------------------------------------------------------------
-- The absence ladder's first rung. These are the people who have actually
-- ridden this territory before, named in advance by someone who knows that -
-- not computed at dispatch time from a distance formula.
CREATE TABLE IF NOT EXISTS subzone_backup_partners (
    id            BIGSERIAL PRIMARY KEY,
    subzone_id    BIGINT NOT NULL,
    partner_id    BIGINT NOT NULL,
    priority      INTEGER
);

ALTER TABLE subzone_backup_partners ADD COLUMN IF NOT EXISTS subzone_id BIGINT;
ALTER TABLE subzone_backup_partners ADD COLUMN IF NOT EXISTS partner_id BIGINT;
ALTER TABLE subzone_backup_partners ADD COLUMN IF NOT EXISTS priority INTEGER;

UPDATE subzone_backup_partners SET priority = 1 WHERE priority IS NULL;
ALTER TABLE subzone_backup_partners ALTER COLUMN priority SET DEFAULT 1;
ALTER TABLE subzone_backup_partners ALTER COLUMN priority SET NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_subzone_backup_partner
    ON subzone_backup_partners (subzone_id, partner_id);
CREATE INDEX IF NOT EXISTS idx_subzone_backup_subzone
    ON subzone_backup_partners (subzone_id, priority);

-- ---------------------------------------------------------------------------
-- Customer address -> permanent subzone
-- ---------------------------------------------------------------------------
-- Stamped when the address is saved, not recomputed per order. Two reasons.
-- First, permanence: an address that resolved to Z7B must still be Z7B next
-- month, and re-running a point-in-polygon test against a boundary an admin
-- edited last week would silently move a customer between territories.
-- Second, cost: checkout preview runs on every cart change, and this keeps
-- the territory lookup off that path entirely.
--
-- subzone_locked marks an address an administrator placed by hand. A house on
-- the wrong side of a boundary line, a gated colony whose only gate opens
-- into the next territory - the map cannot know, so a human overrides it, and
-- nothing automatic may overwrite that.
ALTER TABLE addresses ADD COLUMN IF NOT EXISTS subzone_id BIGINT;
ALTER TABLE addresses ADD COLUMN IF NOT EXISTS subzone_locked BOOLEAN;

UPDATE addresses SET subzone_locked = FALSE WHERE subzone_locked IS NULL;
ALTER TABLE addresses ALTER COLUMN subzone_locked SET DEFAULT FALSE;
ALTER TABLE addresses ALTER COLUMN subzone_locked SET NOT NULL;

CREATE INDEX IF NOT EXISTS idx_addresses_subzone ON addresses (subzone_id);

-- ---------------------------------------------------------------------------
-- Deliveries: which territory, and why this partner
-- ---------------------------------------------------------------------------
-- assignment_reason is the audit trail the whole dynamic half needs. Without
-- it, "why did a Z2 rider deliver in Z7 on Tuesday" is unanswerable, and an
-- overflow that should have been a one-off becomes an invisible habit that
-- quietly argues for redrawing a boundary that was never the problem.
ALTER TABLE deliveries ADD COLUMN IF NOT EXISTS subzone_id BIGINT;
ALTER TABLE deliveries ADD COLUMN IF NOT EXISTS assignment_reason VARCHAR(32);

UPDATE deliveries SET assignment_reason = 'LEGACY' WHERE assignment_reason IS NULL;
ALTER TABLE deliveries ALTER COLUMN assignment_reason SET DEFAULT 'PRIMARY';
ALTER TABLE deliveries ALTER COLUMN assignment_reason SET NOT NULL;

CREATE INDEX IF NOT EXISTS idx_deliveries_subzone ON deliveries (subzone_id);

-- Counting a subzone's live load is the hottest query in the dispatch ladder:
-- it runs once per candidate rung on every assignment. Partial, because a
-- delivered order is not load and the finished rows are the ones that grow
-- without bound.
CREATE INDEX IF NOT EXISTS idx_deliveries_active_by_subzone
    ON deliveries (subzone_id)
    WHERE delivery_status IS DISTINCT FROM 'DELIVERED'
      AND delivery_status IS DISTINCT FROM 'CANCELLED';

-- ---------------------------------------------------------------------------
-- Batches carry the subzone, not a typed area string
-- ---------------------------------------------------------------------------
-- delivery_batches.area is free text copied from whatever the customer typed
-- into their address, matched with =. "Sector 12", "sector 12" and "Sector-12"
-- open three separate batches for one neighbourhood today. The column stays
-- for now so existing rows still read, but grouping moves to subzone_id.
ALTER TABLE delivery_batches ADD COLUMN IF NOT EXISTS subzone_id BIGINT;
CREATE INDEX IF NOT EXISTS idx_delivery_batches_subzone
    ON delivery_batches (subzone_id, status);
