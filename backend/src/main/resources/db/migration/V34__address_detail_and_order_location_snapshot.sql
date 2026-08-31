-- Address detail fields, and the order delivery-location snapshot.
--
-- TWO THINGS, AND THE SECOND ONE IS A BUG FIX.
--
-- 1. The addresses table gains the fields a map-confirmed address needs:
--    a label, the parts of a building, the provider's own formatted string,
--    delivery instructions, and the provenance of the coordinates.
--
-- 2. Orders gain a SNAPSHOT of where they are actually going.
--
-- WHY THE SNAPSHOT EXISTS. orders.address_id is a foreign key to the live
-- addresses row, and AddressService.updateAddress rewrites that row in place
-- - including latitude and longitude. So a customer who moved house and
-- corrected their saved address silently changed the destination of every
-- order still pointing at it, including one already packed and out for
-- delivery: WorkerOrderView reads order.getAddress(), so the rider's screen
-- and their Navigate button would both follow the edit to the new house.
--
-- The code already knew. AddressService.deleteAddress carries the comment
-- "Past orders reference this address directly (not a copy)".
--
-- After this migration the address row is where a customer's CURRENT address
-- lives, and these columns are where an order's destination lives. Editing
-- one cannot move the other.
--
-- ADDITIVE ONLY. Every column is nullable and nothing is dropped or
-- rewritten, so the previous release keeps working against this schema -
-- which matters because the production rollback restores the previous image,
-- not the previous schema.

-- ------------------------------------------------------------- addresses

-- Home / Work / Shop / Other. Free text rather than an enum column: a check
-- constraint here would have to be widened by a migration every time the shop
-- wants a new label, and V32 is the standing lesson about enum values
-- outgrowing the constraint Hibernate generated for them.
ALTER TABLE addresses ADD COLUMN IF NOT EXISTS label VARCHAR(20);

ALTER TABLE addresses ADD COLUMN IF NOT EXISTS building_name VARCHAR(200);
ALTER TABLE addresses ADD COLUMN IF NOT EXISTS floor VARCHAR(50);
ALTER TABLE addresses ADD COLUMN IF NOT EXISTS street VARCHAR(200);

-- NO `locality` COLUMN, DELIBERATELY. addresses.area already holds exactly
-- that and every existing row has it populated. Adding a second column for
-- the same idea would be two fields competing to be the locality, with no
-- rule about which wins - the duplicate-system failure this work is under
-- instruction to avoid. `area` IS the locality; `street` is what was missing.
ALTER TABLE addresses ADD COLUMN IF NOT EXISTS formatted_address VARCHAR(500);
ALTER TABLE addresses ADD COLUMN IF NOT EXISTS delivery_instructions VARCHAR(500);

-- Metres, as reported by the device at the moment the pin was confirmed.
-- Kept because "this pin came from a 40 m cell-tower fix" and "this pin was
-- dragged onto the doorstep" are different facts about the same coordinates,
-- and only the first is worth warning a rider about.
ALTER TABLE addresses ADD COLUMN IF NOT EXISTS location_accuracy DOUBLE PRECISION;

-- Where the coordinates came from. place_id lets a later lookup ask the
-- provider about the same place without re-geocoding free text.
ALTER TABLE addresses ADD COLUMN IF NOT EXISTS place_id VARCHAR(255);
ALTER TABLE addresses ADD COLUMN IF NOT EXISTS geocoding_provider VARCHAR(40);

ALTER TABLE addresses ADD COLUMN IF NOT EXISTS created_at TIMESTAMP;
ALTER TABLE addresses ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP;

-- When the customer last confirmed the pin on a map, as opposed to when the
-- row was last touched. Null means these coordinates have never been through
-- the map confirmation step - true of every address saved before this work.
ALTER TABLE addresses ADD COLUMN IF NOT EXISTS confirmed_at TIMESTAMP;

-- GET /api/addresses/mine runs on every checkout and filters on customer_id.
-- The only index this table had was on subzone_id.
CREATE INDEX IF NOT EXISTS idx_addresses_customer ON addresses (customer_id);

-- Serviceability and support both look addresses up by PIN.
CREATE INDEX IF NOT EXISTS idx_addresses_pincode
    ON addresses (pincode)
    WHERE pincode IS NOT NULL;

-- ---------------------------------------------------------------- orders

ALTER TABLE orders ADD COLUMN IF NOT EXISTS delivery_recipient_name VARCHAR(200);
ALTER TABLE orders ADD COLUMN IF NOT EXISTS delivery_recipient_phone VARCHAR(20);
ALTER TABLE orders ADD COLUMN IF NOT EXISTS delivery_house_no VARCHAR(120);
ALTER TABLE orders ADD COLUMN IF NOT EXISTS delivery_building_name VARCHAR(200);
ALTER TABLE orders ADD COLUMN IF NOT EXISTS delivery_floor VARCHAR(50);
ALTER TABLE orders ADD COLUMN IF NOT EXISTS delivery_street VARCHAR(200);
ALTER TABLE orders ADD COLUMN IF NOT EXISTS delivery_area VARCHAR(200);
ALTER TABLE orders ADD COLUMN IF NOT EXISTS delivery_city VARCHAR(120);
ALTER TABLE orders ADD COLUMN IF NOT EXISTS delivery_district VARCHAR(120);
ALTER TABLE orders ADD COLUMN IF NOT EXISTS delivery_state VARCHAR(120);
ALTER TABLE orders ADD COLUMN IF NOT EXISTS delivery_pincode VARCHAR(12);
ALTER TABLE orders ADD COLUMN IF NOT EXISTS delivery_country VARCHAR(80);
ALTER TABLE orders ADD COLUMN IF NOT EXISTS delivery_landmark VARCHAR(300);
ALTER TABLE orders ADD COLUMN IF NOT EXISTS delivery_instructions VARCHAR(500);
ALTER TABLE orders ADD COLUMN IF NOT EXISTS delivery_formatted_address VARCHAR(500);

-- DOUBLE PRECISION, not NUMERIC(9,6) and certainly not an integer. Six
-- decimal places is roughly 0.11 m at the equator, which is the precision a
-- doorstep needs; a low-precision type would round a pin off the building
-- it was dragged onto.
ALTER TABLE orders ADD COLUMN IF NOT EXISTS delivery_latitude DOUBLE PRECISION;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS delivery_longitude DOUBLE PRECISION;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS delivery_location_accuracy DOUBLE PRECISION;

-- When the snapshot was taken. Null marks a pre-V34 order, which is how the
-- read path knows to fall back to the linked address rather than show a
-- rider a blank destination.
ALTER TABLE orders ADD COLUMN IF NOT EXISTS delivery_snapshot_at TIMESTAMP;

-- BACKFILL, and what it can and cannot promise.
--
-- Existing orders get their CURRENT linked address copied in. That is the
-- best available answer and it is not necessarily the address as it stood
-- when the order was placed - if a customer already edited a saved address,
-- that edit is already baked into the row and this migration cannot recover
-- what was there before. It stops the drift here rather than pretending to
-- undo it.
--
-- delivery_snapshot_at is deliberately left NULL for these rows: they are
-- reconstructions, not snapshots taken at placement, and the read path and
-- any future audit should be able to tell the difference.
UPDATE orders o
SET delivery_recipient_name  = a.full_name,
    delivery_recipient_phone = a.mobile_number,
    delivery_house_no        = a.house_no,
    delivery_area            = a.area,
    delivery_city            = a.city,
    delivery_district        = a.district,
    delivery_state           = a.state,
    delivery_pincode         = a.pincode,
    delivery_country         = a.country,
    delivery_landmark        = a.landmark,
    delivery_latitude        = a.latitude,
    delivery_longitude       = a.longitude
FROM addresses a
WHERE o.address_id = a.id
  AND o.delivery_latitude IS NULL
  AND o.delivery_house_no IS NULL;
