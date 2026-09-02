#!/bin/sh
# Puts a shop's worth of rows in a database, so a migration can be rehearsed
# against DATA instead of against emptiness.
#
# WHY THIS EXISTS. CI already proves every migration APPLIES to an empty
# database. That proof is silent about the failures that only happen once
# there are rows in the way:
#
#   * ADD COLUMN ... NOT NULL with no DEFAULT - fine on an empty table,
#     rejected the moment one row exists.
#   * CREATE UNIQUE INDEX over a column that already holds duplicates.
#   * A narrowed type, or a CHECK constraint, that existing values fail.
#   * An UPDATE/backfill whose WHERE clause turns out to match nothing, or
#     everything.
#
# There is no staging environment. Without this, the shop's own database is
# the first place any migration ever meets a real row, and the first sign of
# trouble is a deploy that stops halfway with the schema half-changed.
#
# WHAT IT DELIBERATELY SEEDS. Not one row per table - rows that are shaped
# like the awkward cases:
#
#   * TWO orders for the SAME customer, and two payments, so a unique index
#     added over a column these rows share fails here rather than there.
#   * A worker who is soft-deleted alongside one who is not, because
#     deleted_at IS NULL is the condition half the worker queries filter on
#     and a partial index over it needs both kinds present to mean anything.
#   * A COD payment and a prepaid one with a provider order id, since those
#     two go down different code paths in almost every payment migration.
#   * NULLs left where the schema allows them (no landmark, no vehicle
#     number, no transaction id on the pending payment) - a NOT NULL added
#     later must trip over these.
#
# CONTAINS NO SECRET AND NO REAL PERSON. Every value here is obviously
# synthetic: example.com addresses, 90000000xx phone numbers reserved for
# this script, and a password_hash that is a valid bcrypt structure for a
# password nobody knows and no login path here ever checks. This runs only
# against a throwaway CI database. It must never be pointed at production -
# and it would refuse to be useful there anyway, since these rows would
# collide with the unique indexes on email and mobile.
#
# Usage: seed-rehearsal-rows.sh HOST PORT USER DBNAME
#        PGPASSWORD supplies the password, as psql expects. Never pass one
#        on the command line - that puts it in the process list.
set -eu

HOST="${1:?usage: seed-rehearsal-rows.sh HOST PORT USER DBNAME}"
PORT="${2:?usage: seed-rehearsal-rows.sh HOST PORT USER DBNAME}"
USER="${3:?usage: seed-rehearsal-rows.sh HOST PORT USER DBNAME}"
DBNAME="${4:?usage: seed-rehearsal-rows.sh HOST PORT USER DBNAME}"

# A refusal, not a warning. Somebody will eventually run this with the wrong
# argument, and the cost of that mistake against a live database is rows
# that look real sitting in the shop's books forever.
case "$DBNAME" in
  *rehearsal*|*test*|*scratch*) : ;;
  *)
    echo "REFUSING: '$DBNAME' is not a rehearsal database." >&2
    echo "This inserts synthetic rows and is only for throwaway CI databases." >&2
    echo "Name the database with 'rehearsal', 'test' or 'scratch' in it." >&2
    exit 2
    ;;
esac

echo "Seeding rehearsal rows into $DBNAME on $HOST:$PORT"

# ON_ERROR_STOP so a constraint we did not anticipate fails the job here,
# loudly, instead of leaving a half-seeded database for the migration step to
# produce a confusing error against.
psql -h "$HOST" -p "$PORT" -U "$USER" -d "$DBNAME" -v ON_ERROR_STOP=1 -q <<'SQL'
BEGIN;

-- Re-runnable. Children first: orders point at customers and workers,
-- payments point at orders.
DELETE FROM payments WHERE order_id IN (
    SELECT id FROM orders WHERE order_number LIKE 'REHEARSAL-%');
DELETE FROM orders WHERE order_number LIKE 'REHEARSAL-%';
DELETE FROM delivery_partners WHERE mobile IN ('9000000002', '9000000003');
DELETE FROM customers WHERE email = 'rehearsal@example.com';

-- The customer both orders belong to.
INSERT INTO customers (full_name, email, mobile_number, password, role,
                       enabled, verified, active)
VALUES ('Rehearsal Customer', 'rehearsal@example.com', '9000000001',
        '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy',
        'CUSTOMER', TRUE, TRUE, TRUE);

-- Two workers: one working, one soft-deleted. Both must exist for a partial
-- index or a backfill keyed on deleted_at to be exercised at all.
INSERT INTO delivery_partners (name, mobile, login_email, password_hash,
                               vehicle_type, vehicle_number,
                               available, active, deleted_at)
VALUES ('Rehearsal Worker', '9000000002', 'worker.rehearsal@example.com',
        '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy',
        'BIKE', 'UP16AB1234', TRUE, TRUE, NULL),
       ('Rehearsal Ex-Worker', '9000000003', 'exworker.rehearsal@example.com',
        '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy',
        NULL, NULL, FALSE, FALSE, NOW() - INTERVAL '30 days');

-- A delivered order and a still-open one, both for the same customer, with
-- the delivery address snapshot filled in the way checkout writes it.
INSERT INTO orders (order_number, customer_id, assigned_worker_partner_id,
                    total_amount, delivery_fee, discount_amount,
                    order_status, payment_status, order_date,
                    inventory_restored, active,
                    delivery_recipient_name, delivery_recipient_phone,
                    delivery_house_no, delivery_street, delivery_area,
                    delivery_city, delivery_district, delivery_state,
                    delivery_pincode, delivery_country,
                    delivery_latitude, delivery_longitude,
                    delivery_snapshot_at)
SELECT 'REHEARSAL-1', c.id, w.id,
       499.00, 0.00, 0.00,
       'DELIVERED', 'SUCCESS', NOW() - INTERVAL '3 days',
       FALSE, TRUE,
       'Rehearsal Customer', '9000000001',
       '12', 'Station Road', 'Civil Lines',
       'Ghaziabad', 'Ghaziabad', 'Uttar Pradesh',
       '201001', 'India',
       28.6692, 77.4538,
       NOW() - INTERVAL '3 days'
FROM customers c
CROSS JOIN delivery_partners w
WHERE c.email = 'rehearsal@example.com' AND w.mobile = '9000000002';

INSERT INTO orders (order_number, customer_id,
                    total_amount, delivery_fee, discount_amount,
                    order_status, payment_status, order_date,
                    inventory_restored, active,
                    delivery_recipient_name, delivery_recipient_phone,
                    delivery_house_no, delivery_street, delivery_area,
                    delivery_city, delivery_district, delivery_state,
                    delivery_pincode, delivery_country,
                    delivery_latitude, delivery_longitude,
                    delivery_snapshot_at)
SELECT 'REHEARSAL-2', c.id,
       250.00, 25.00, 0.00,
       'PENDING_CONFIRMATION', 'COD_PENDING', NOW() - INTERVAL '1 hour',
       FALSE, TRUE,
       'Rehearsal Customer', '9000000001',
       '12', 'Station Road', 'Civil Lines',
       'Ghaziabad', 'Ghaziabad', 'Uttar Pradesh',
       '201001', 'India',
       28.6692, 77.4538,
       NOW() - INTERVAL '1 hour'
FROM customers c
WHERE c.email = 'rehearsal@example.com';

-- One settled prepaid payment carrying a provider order id, and one COD
-- payment that has no transaction id at all. Payment migrations almost
-- always treat those two differently, so both need to be here.
INSERT INTO payments (order_id, amount, currency, payment_method,
                      payment_status, provider, provider_order_id,
                      provider_payment_id, transaction_id,
                      payment_date, updated_at, active)
SELECT o.id, 499.00, 'INR', 'ONLINE', 'SUCCESS',
       'CASHFREE', 'cf-rehearsal-order-1', 'cf-rehearsal-payment-1',
       'cf-rehearsal-payment-1',
       NOW() - INTERVAL '3 days', NOW() - INTERVAL '3 days', TRUE
FROM orders o WHERE o.order_number = 'REHEARSAL-1';

INSERT INTO payments (order_id, amount, currency, payment_method,
                      payment_status, provider, provider_order_id,
                      provider_payment_id, transaction_id,
                      payment_date, updated_at, active)
SELECT o.id, 275.00, 'INR', 'COD', 'COD_PENDING',
       NULL, NULL, NULL, NULL,
       NOW() - INTERVAL '1 hour', NOW() - INTERVAL '1 hour', TRUE
FROM orders o WHERE o.order_number = 'REHEARSAL-2';

COMMIT;
SQL

# Seeding nothing at all would let the whole rehearsal pass vacuously - the
# migration would apply to an empty table and the assertion afterwards would
# be checking rows that were never there. Count them before claiming success.
SEEDED="$(psql -h "$HOST" -p "$PORT" -U "$USER" -d "$DBNAME" -Atc "
  SELECT (SELECT COUNT(*) FROM customers WHERE email = 'rehearsal@example.com')
       + (SELECT COUNT(*) FROM delivery_partners WHERE mobile IN ('9000000002','9000000003'))
       + (SELECT COUNT(*) FROM orders WHERE order_number LIKE 'REHEARSAL-%')
       + (SELECT COUNT(*) FROM payments WHERE order_id IN
            (SELECT id FROM orders WHERE order_number LIKE 'REHEARSAL-%'));")"

if [ "$SEEDED" != "7" ]; then
  echo "SEED FAILED: expected 7 rehearsal rows, found ${SEEDED}." >&2
  exit 1
fi

echo "Seeded 7 rows: 1 customer, 2 workers, 2 orders, 2 payments."
