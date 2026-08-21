-- Cashfree, the first payment gateway this project has had.
--
-- MERGE ORDER: V13 (product 3D model) is on the claude/api-hardening branch
-- and must land BEFORE this one. spring.flyway.out-of-order is not enabled,
-- so applying V14 first would make V13 arrive out of sequence and fail the
-- migration on the next deploy.
--
-- EXTENDS payments RATHER THAN REPLACING IT. The existing table already
-- holds the things a payment is - amount, method, status, a unique
-- transaction id, the 1:1 order link - and PaymentService already advances
-- the order only once payment is real. What it lacks is anywhere to record
-- WHICH provider, and the two ids that provider knows the payment by.

ALTER TABLE payments
    -- Null for every existing COD and direct-UPI row, and it stays null for
    -- them: those are not gateway payments and pretending otherwise would
    -- make reconciliation reports lie.
    ADD COLUMN IF NOT EXISTS provider            VARCHAR(32),
    ADD COLUMN IF NOT EXISTS provider_order_id   VARCHAR(120),
    ADD COLUMN IF NOT EXISTS provider_payment_id VARCHAR(120),
    -- Explicit rather than assumed. The gateway echoes a currency back on
    -- every webhook, and a payment whose currency is not the one we asked
    -- for must be rejected rather than quietly banked.
    ADD COLUMN IF NOT EXISTS currency            VARCHAR(3),
    ADD COLUMN IF NOT EXISTS failure_reason      VARCHAR(255),
    ADD COLUMN IF NOT EXISTS updated_at          TIMESTAMP;

-- UNIQUE, not merely indexed. These are the constraints that make duplicate
-- protection a property of the database rather than of the code path that
-- happens to run: two orders can never share one Cashfree order, and one
-- Cashfree payment can never be banked against two of ours. A concurrent
-- webhook and client callback both racing to write the same id lose at the
-- constraint, not at a check-then-act that both passed.
--
-- Partial (WHERE NOT NULL) so the thousands of COD rows carrying NULL do not
-- collide with each other - in Postgres NULLs are distinct in a unique index
-- anyway, but stating it keeps the index small and the intent legible.
CREATE UNIQUE INDEX IF NOT EXISTS ux_payments_provider_order_id
    ON payments (provider_order_id) WHERE provider_order_id IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS ux_payments_provider_payment_id
    ON payments (provider_payment_id) WHERE provider_payment_id IS NOT NULL;

-- Every webhook the gateway ever delivers, recorded before it is acted on.
--
-- THIS TABLE IS THE DUPLICATE PROTECTION. Cashfree retries delivery until it
-- gets a 2xx, and a retry is indistinguishable from the first attempt except
-- by its id. Inserting the id under a unique constraint FIRST, inside the
-- same transaction that applies the payment change, means the second
-- delivery cannot apply anything: it fails the insert and the whole
-- transaction rolls back, having changed nothing.
--
-- It is also the reconciliation record. When a customer says they paid and
-- the order says otherwise, this is the table that settles it.
CREATE TABLE IF NOT EXISTS payment_provider_events (
    id            BIGSERIAL PRIMARY KEY,
    provider      VARCHAR(32)  NOT NULL,
    -- The provider's own id for this delivery.
    event_id      VARCHAR(160) NOT NULL,
    event_type    VARCHAR(64),
    -- Nullable: an event can arrive for an order we do not recognise, and
    -- that is worth recording precisely BECAUSE we cannot act on it.
    payment_id    BIGINT REFERENCES payments (id),
    provider_order_id VARCHAR(120),
    -- What we decided and why, so a rejected event can be explained later
    -- without re-deriving it from logs that have since rotated away.
    outcome       VARCHAR(32)  NOT NULL,
    detail        VARCHAR(500),
    received_at   TIMESTAMP    NOT NULL DEFAULT NOW()
);

-- The constraint that makes a retried webhook a no-op.
CREATE UNIQUE INDEX IF NOT EXISTS ux_payment_events_provider_event
    ON payment_provider_events (provider, event_id);

-- Reconciliation reads this by order far more often than by id.
CREATE INDEX IF NOT EXISTS idx_payment_events_provider_order
    ON payment_provider_events (provider_order_id);

CREATE INDEX IF NOT EXISTS idx_payment_events_payment_id
    ON payment_provider_events (payment_id);
