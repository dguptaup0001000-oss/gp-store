-- Worker pack-scan: a QR token on the order, and an audit trail of who
-- scanned what.
--
-- WHAT THIS IS FOR. GP-STORE's delivery workers are shop employees who also
-- deliver. When an order is packed, a worker scans its QR code and the backend
-- records that THIS worker took THAT order. The admin can then answer "who has
-- GP125" without asking anybody. The customer is told one thing - that their
-- order is packed - and nothing about a journey that has not started.
--
-- WHY THE TOKEN IS A COLUMN AND NOT A TABLE. An order has at most one live QR
-- code, and a table would buy history nobody reads at the cost of a join on
-- the hottest path in the scanning flow. Re-issuing overwrites, and the scan
-- audit below already records every token that was actually used.
--
-- THE NOT NULL DANCE, as in V17/V18/V19: Hibernate runs before Flyway here and
-- cannot add a NOT NULL column to a populated table, so anything added to
-- orders is created nullable and tightened by an explicit ALTER after a
-- backfill. "ADD COLUMN IF NOT EXISTS ... NOT NULL" silently does nothing when
-- the column already exists, which is how V15 shipped a nullable column that
-- production then broke on.

-- ---------------------------------------------------------------------------
-- The QR token on the order
-- ---------------------------------------------------------------------------
-- SINGLE USE AND OPAQUE. The token carries no customer name, phone, address,
-- amount or payment state - it is a random string that means nothing to anyone
-- who photographs the label off a discarded carton. Everything the worker's app
-- shows comes back from an authenticated call, so the paper is not a secret
-- worth stealing.
--
-- It is consumed by the first successful scan (qr_token_used_at set), which is
-- what makes "another worker cannot scan the same order" true in the database
-- rather than merely true in the app.
ALTER TABLE orders ADD COLUMN IF NOT EXISTS qr_token VARCHAR(64);
ALTER TABLE orders ADD COLUMN IF NOT EXISTS qr_token_issued_at TIMESTAMP;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS qr_token_used_at TIMESTAMP;

-- The scan looks the order up BY TOKEN - the app never sends an order id,
-- because a client that can name the order is a client that can guess one.
-- Unique so two orders can never share a token; partial so the index holds
-- only orders that currently have one.
CREATE UNIQUE INDEX IF NOT EXISTS uq_orders_qr_token
    ON orders (qr_token)
    WHERE qr_token IS NOT NULL;

-- Which worker is accountable for this order right now. Denormalised onto the
-- order on purpose: "who has GP125" is the question the admin screen asks on
-- every row of every list, and answering it through the scan history would be
-- a subquery per order.
ALTER TABLE orders ADD COLUMN IF NOT EXISTS packed_by_partner_id BIGINT;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS packed_at TIMESTAMP;

CREATE INDEX IF NOT EXISTS idx_orders_packed_by
    ON orders (packed_by_partner_id)
    WHERE packed_by_partner_id IS NOT NULL;

-- ---------------------------------------------------------------------------
-- Admin override: this worker may take this order, whatever the map says
-- ---------------------------------------------------------------------------
-- The escape hatch for the day the territory rules are wrong: the primary is
-- absent and unrostered, a worker is already at the far village, a subzone has
-- not been drawn yet. Set by an admin, checked by the authorisation ladder
-- ahead of every territory rule.
ALTER TABLE orders ADD COLUMN IF NOT EXISTS assigned_worker_partner_id BIGINT;

CREATE INDEX IF NOT EXISTS idx_orders_assigned_worker
    ON orders (assigned_worker_partner_id)
    WHERE assigned_worker_partner_id IS NOT NULL;

-- ---------------------------------------------------------------------------
-- The audit trail
-- ---------------------------------------------------------------------------
-- EVERY scan attempt, not only the successful ones. A rejected scan is the
-- more interesting record: it is a worker standing at the counter being told
-- no, and the reason is the difference between "the map is wrong" and "someone
-- else already took it". Storing only successes would make the system
-- unanswerable exactly when somebody is asking.
--
-- The zone and subzone codes are COPIED rather than joined. This is a record
-- of what was true at 12:42:18, and a territory that is later renamed or
-- redrawn must not silently rewrite last month's history.
CREATE TABLE IF NOT EXISTS order_scan_events (
    id                 BIGSERIAL PRIMARY KEY,
    order_id           BIGINT,
    order_number       VARCHAR(64),
    partner_id         BIGINT,
    worker_name        VARCHAR(120),
    action             VARCHAR(32)  NOT NULL,
    outcome            VARCHAR(32)  NOT NULL,
    reason             VARCHAR(500),
    zone_code          VARCHAR(16),
    subzone_code       VARCHAR(16),
    scanned_at         TIMESTAMP    NOT NULL,
    client_request_id  VARCHAR(80),
    performed_by_admin BOOLEAN
);

ALTER TABLE order_scan_events ADD COLUMN IF NOT EXISTS order_id BIGINT;
ALTER TABLE order_scan_events ADD COLUMN IF NOT EXISTS order_number VARCHAR(64);
ALTER TABLE order_scan_events ADD COLUMN IF NOT EXISTS partner_id BIGINT;
ALTER TABLE order_scan_events ADD COLUMN IF NOT EXISTS worker_name VARCHAR(120);
ALTER TABLE order_scan_events ADD COLUMN IF NOT EXISTS action VARCHAR(32);
ALTER TABLE order_scan_events ADD COLUMN IF NOT EXISTS outcome VARCHAR(32);
ALTER TABLE order_scan_events ADD COLUMN IF NOT EXISTS reason VARCHAR(500);
ALTER TABLE order_scan_events ADD COLUMN IF NOT EXISTS zone_code VARCHAR(16);
ALTER TABLE order_scan_events ADD COLUMN IF NOT EXISTS subzone_code VARCHAR(16);
ALTER TABLE order_scan_events ADD COLUMN IF NOT EXISTS scanned_at TIMESTAMP;
ALTER TABLE order_scan_events ADD COLUMN IF NOT EXISTS client_request_id VARCHAR(80);
ALTER TABLE order_scan_events ADD COLUMN IF NOT EXISTS performed_by_admin BOOLEAN;

UPDATE order_scan_events SET performed_by_admin = FALSE WHERE performed_by_admin IS NULL;
ALTER TABLE order_scan_events ALTER COLUMN performed_by_admin SET DEFAULT FALSE;
ALTER TABLE order_scan_events ALTER COLUMN performed_by_admin SET NOT NULL;

-- IDEMPOTENCY, ENFORCED BY THE DATABASE.
--
-- A worker on a weak connection taps scan, sees nothing happen, and taps
-- again. The app sends the same client_request_id both times. Without this
-- index the second request creates a second scan record and a second
-- notification; with it, the insert collides and the service replays the first
-- result instead.
--
-- Scoped to the worker, not global: two workers generating the same random id
-- is vanishingly unlikely but the failure would be one of them silently
-- receiving the other's answer, which is worse than the collision it prevents.
CREATE UNIQUE INDEX IF NOT EXISTS uq_scan_client_request
    ON order_scan_events (partner_id, client_request_id)
    WHERE client_request_id IS NOT NULL;

-- "Show me everything that happened to this order", newest first - the admin
-- screen's only query against this table.
CREATE INDEX IF NOT EXISTS idx_scan_events_order
    ON order_scan_events (order_id, scanned_at DESC);

-- "What did this worker do today", which is the accountability question the
-- whole feature exists to answer.
CREATE INDEX IF NOT EXISTS idx_scan_events_partner
    ON order_scan_events (partner_id, scanned_at DESC);

-- ---------------------------------------------------------------------------
-- Let the database accept the new status at all
-- ---------------------------------------------------------------------------
-- Hibernate generates a CHECK constraint listing every value of a string enum,
-- and ddl-auto=update NEVER alters it afterwards. So adding PACKED to
-- OrderStatus compiles, passes on any freshly-created database, and then fails
-- on every database that already existed:
--
--     new row for relation "orders" violates check constraint
--     "orders_order_status_check"
--
-- That is not a hypothetical. It is what happened the first time these tests
-- ran against a database created before the enum grew, and it is exactly what
-- production is - a database created months ago.
--
-- The constraint name is discovered rather than assumed: Hibernate has used
-- more than one naming scheme across versions, and a DROP of a name that does
-- not exist would leave the real constraint in place and the failure intact.
DO $$
DECLARE
    constraint_name TEXT;
BEGIN
    FOR constraint_name IN
        SELECT con.conname
        FROM pg_constraint con
        JOIN pg_class rel ON rel.oid = con.conrelid
        WHERE rel.relname = 'orders'
          AND con.contype = 'c'
          AND pg_get_constraintdef(con.oid) ILIKE '%order_status%'
    LOOP
        EXECUTE format('ALTER TABLE orders DROP CONSTRAINT %I', constraint_name);
    END LOOP;
END $$;

-- Recreated explicitly with every current value, rather than left to Hibernate
-- to regenerate: whether ddl-auto re-adds a dropped check varies by version,
-- and a status column with no constraint at all would silently accept a typo.
ALTER TABLE orders ADD CONSTRAINT orders_order_status_check
    CHECK (order_status IS NULL OR order_status IN (
        'PENDING_CONFIRMATION',
        'CONFIRMED',
        'PACKING',
        'PACKED',
        'READY_TO_DISPATCH',
        'OUT_FOR_DELIVERY',
        'DELIVERED',
        'CANCELLED'
    ));
