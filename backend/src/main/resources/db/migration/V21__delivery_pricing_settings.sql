-- V1 delivery pricing: distance tiers, a weight surcharge, and a margin-based
-- subsidy - all of it editable without rebuilding the app.
--
-- WHY A TABLE AND NOT PROPERTIES. The old rule (base + per-km, in
-- application.properties) could only be changed by a redeploy, which meant the
-- shop could not respond to a fuel price or a bad week without waiting for
-- someone with a laptop. Every number below is one a shopkeeper has an opinion
-- about, so every number below is a row they can edit.
--
-- ONE ROW, ENFORCED. A settings table with two rows is a settings table that
-- silently applies whichever one a query happened to return first. The
-- singleton check constraint below makes a second row impossible rather than
-- merely unlikely.

CREATE TABLE IF NOT EXISTS delivery_pricing_settings (
    id                          BIGINT PRIMARY KEY,

    -- Distance tiers. 0-1 km flat, 1-2 km flat, then per whole additional km.
    distance_tier1_charge       NUMERIC(10, 2),
    distance_tier1_max_km       NUMERIC(10, 2),
    distance_tier2_charge       NUMERIC(10, 2),
    distance_tier2_max_km       NUMERIC(10, 2),
    additional_km_charge        NUMERIC(10, 2),

    -- Weight surcharge. The first N kg are free, then per kg, capped.
    free_weight_kg              NUMERIC(10, 3),
    additional_weight_per_kg    NUMERIC(10, 2),
    maximum_weight_surcharge    NUMERIC(10, 2),

    -- The margin rule: free delivery needs profit >= multiplier x normal charge.
    free_delivery_multiplier    NUMERIC(10, 2),

    -- ROAD DISTANCE. The pricing rule is written in road kilometres, and this
    -- deployment has no routing provider - only a straight-line distance from
    -- the shop. A factor of 1.00 means "quote the straight line as-is", which
    -- systematically UNDER-charges, because no road is straighter than the
    -- line. Left at 1.00 rather than guessed at 1.3: an invented multiplier
    -- charging every customer for kilometres nobody measured is worse than a
    -- known, honest under-charge, and the quote says which one it used.
    road_distance_factor        NUMERIC(10, 3),

    -- What one piece-counted item is assumed to weigh when nothing else says.
    -- Zero by default: a fabricated weight charges a customer for a number
    -- nobody measured. The quote lists every item that hit this so the shop
    -- can fill in the real figures.
    assumed_weight_per_item_kg  NUMERIC(10, 3),

    updated_at                  TIMESTAMP,
    updated_by                  VARCHAR(120)
);

-- Columns added individually as well, for a table an earlier ddl-auto already
-- created - "ADD COLUMN IF NOT EXISTS" on an existing table is a no-op, and a
-- CREATE TABLE IF NOT EXISTS on an existing table silently skips every column
-- it would have added.
ALTER TABLE delivery_pricing_settings ADD COLUMN IF NOT EXISTS distance_tier1_charge NUMERIC(10, 2);
ALTER TABLE delivery_pricing_settings ADD COLUMN IF NOT EXISTS distance_tier1_max_km NUMERIC(10, 2);
ALTER TABLE delivery_pricing_settings ADD COLUMN IF NOT EXISTS distance_tier2_charge NUMERIC(10, 2);
ALTER TABLE delivery_pricing_settings ADD COLUMN IF NOT EXISTS distance_tier2_max_km NUMERIC(10, 2);
ALTER TABLE delivery_pricing_settings ADD COLUMN IF NOT EXISTS additional_km_charge NUMERIC(10, 2);
ALTER TABLE delivery_pricing_settings ADD COLUMN IF NOT EXISTS free_weight_kg NUMERIC(10, 3);
ALTER TABLE delivery_pricing_settings ADD COLUMN IF NOT EXISTS additional_weight_per_kg NUMERIC(10, 2);
ALTER TABLE delivery_pricing_settings ADD COLUMN IF NOT EXISTS maximum_weight_surcharge NUMERIC(10, 2);
ALTER TABLE delivery_pricing_settings ADD COLUMN IF NOT EXISTS free_delivery_multiplier NUMERIC(10, 2);
ALTER TABLE delivery_pricing_settings ADD COLUMN IF NOT EXISTS road_distance_factor NUMERIC(10, 3);
ALTER TABLE delivery_pricing_settings ADD COLUMN IF NOT EXISTS assumed_weight_per_item_kg NUMERIC(10, 3);
ALTER TABLE delivery_pricing_settings ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP;
ALTER TABLE delivery_pricing_settings ADD COLUMN IF NOT EXISTS updated_by VARCHAR(120);

-- The V1 numbers from the brief. Inserted rather than defaulted in code so the
-- shop can see and edit them from the first boot instead of discovering that
-- the "configurable" values live in a constant somewhere.
INSERT INTO delivery_pricing_settings (
    id, distance_tier1_charge, distance_tier1_max_km,
    distance_tier2_charge, distance_tier2_max_km, additional_km_charge,
    free_weight_kg, additional_weight_per_kg, maximum_weight_surcharge,
    free_delivery_multiplier, road_distance_factor, assumed_weight_per_item_kg,
    updated_at, updated_by
) VALUES (
    1, 5.00, 1.00,
    10.00, 2.00, 5.00,
    10.000, 2.00, 20.00,
    3.00, 1.000, 0.000,
    now(), 'V21 migration'
)
ON CONFLICT (id) DO NOTHING;

-- One row, ever.
ALTER TABLE delivery_pricing_settings DROP CONSTRAINT IF EXISTS delivery_pricing_settings_singleton;
ALTER TABLE delivery_pricing_settings ADD CONSTRAINT delivery_pricing_settings_singleton CHECK (id = 1);

-- ---------------------------------------------------------------------------
-- Product weight
-- ---------------------------------------------------------------------------
-- The catalogue has no weight column. It has a pack quantity and a unit, and
-- for most of the shelf that is enough: 557 variants are sold in grams, 172 in
-- kilograms, 256 in millilitres or litres. 625 are sold by the piece, and for
-- those nothing in the data says what they weigh.
--
-- So this column is the OVERRIDE, not the source. Where it is set it wins;
-- where it is null the weight is derived from quantity and unit; where neither
-- works the item contributes the configured assumption (zero by default) and
-- the quote names it, so "which products need weighing" is a list somebody can
-- work through rather than a mystery.
ALTER TABLE product_variants ADD COLUMN IF NOT EXISTS weight_grams NUMERIC(10, 2);

-- ---------------------------------------------------------------------------
-- The breakdown, kept on the order
-- ---------------------------------------------------------------------------
-- Stored rather than recomputed, because a quote is a statement made at a
-- moment: the settings can change tomorrow, the catalogue's costs can change,
-- and an admin screen that recalculated would show a number the customer was
-- never charged. Every one of these is a line the brief asks the admin to see.
ALTER TABLE orders ADD COLUMN IF NOT EXISTS delivery_distance_km NUMERIC(10, 3);
ALTER TABLE orders ADD COLUMN IF NOT EXISTS delivery_weight_kg NUMERIC(10, 3);
ALTER TABLE orders ADD COLUMN IF NOT EXISTS delivery_distance_charge NUMERIC(10, 2);
ALTER TABLE orders ADD COLUMN IF NOT EXISTS delivery_weight_charge NUMERIC(10, 2);
ALTER TABLE orders ADD COLUMN IF NOT EXISTS delivery_normal_charge NUMERIC(10, 2);
ALTER TABLE orders ADD COLUMN IF NOT EXISTS delivery_order_profit NUMERIC(10, 2);
ALTER TABLE orders ADD COLUMN IF NOT EXISTS delivery_subsidy NUMERIC(10, 2);
ALTER TABLE orders ADD COLUMN IF NOT EXISTS delivery_pricing_notes VARCHAR(1000);
