-- Cash actually collected at the door, and what the rider thought of the stop.
--
-- IF NOT EXISTS on everything, like every other migration here. Flyway runs
-- AFTER Hibernate's schema generation in this project (see
-- FlywayAfterSchemaConfig), so by the time this file runs the columns may
-- already have been created from the entity. A bare ADD COLUMN would fail the
-- bootstrap path; this one is a no-op there and does the real work on a
-- production database where ddl-auto is validate.

-- ---------------------------------------------------------------- COD split
--
-- WHY TWO AMOUNTS RATHER THAN ONE "method" COLUMN. A customer at the door may
-- hand over part in notes and pay the rest by scanning the shop's QR. One
-- enum cannot record that, and rounding it to whichever was larger would put
-- a wrong number in the shop's cash reconciliation - the whole reason these
-- columns exist. Nullable: an order settled before this feature, or settled
-- automatically when a delivery was marked delivered, has no split recorded
-- and must not pretend to.
ALTER TABLE payments ADD COLUMN IF NOT EXISTS cod_cash_amount NUMERIC(12, 2);
ALTER TABLE payments ADD COLUMN IF NOT EXISTS cod_upi_amount NUMERIC(12, 2);

-- Who took the money. Deliberately NOT a foreign key to delivery_partners:
-- this is an accounting record and it has to outlive the rider's employment,
-- exactly like the address on a delivered order outlives the customer account.
ALTER TABLE payments ADD COLUMN IF NOT EXISTS cod_collected_by_partner_id BIGINT;
ALTER TABLE payments ADD COLUMN IF NOT EXISTS cod_collected_at TIMESTAMP;

-- ------------------------------------------------------- customer ratings
--
-- The rider's read on a delivery, 1-10. ADMIN EYES ONLY - never returned on
-- any customer-facing endpoint, never shown to the person rated, and never
-- used to decide anything about their orders. It exists so a shopkeeper can
-- see a pattern (repeatedly absent, address impossible to find, abusive)
-- rather than relying on one rider's memory.
CREATE TABLE IF NOT EXISTS customer_delivery_ratings (
    id                  BIGSERIAL PRIMARY KEY,
    order_id            BIGINT      NOT NULL,
    customer_id         BIGINT      NOT NULL,
    partner_id          BIGINT,
    score               INTEGER     NOT NULL,
    created_at          TIMESTAMP   NOT NULL DEFAULT NOW()
);

-- ONE RATING PER ORDER. Without this a rider could tap the button twice and
-- move a customer's average on a single delivery, which is exactly the kind
-- of quiet data drift nobody notices until the number is meaningless.
CREATE UNIQUE INDEX IF NOT EXISTS ux_customer_rating_order
    ON customer_delivery_ratings (order_id);

-- The admin screen reads "this customer's ratings", newest first.
CREATE INDEX IF NOT EXISTS ix_customer_rating_customer
    ON customer_delivery_ratings (customer_id, created_at DESC);
