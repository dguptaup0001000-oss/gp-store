-- A refund becomes a thing that happened, not just a status.
--
-- WHY THIS EXISTS. Refunding used to be two status changes and an audit line:
-- REFUND_PENDING, then REFUNDED. Nothing ever left the building. For a COD
-- order that is honest - the cash goes back at the door and the row is the
-- record. For a PREPAID order it was a lie: the admin screen said REFUNDED,
-- the audit log said REFUNDED, and the customer's money was still sitting at
-- Cashfree until somebody remembered to refund it by hand in their dashboard.
--
-- The refund now goes to the provider, and these columns are what let the
-- application ask "did it actually land?" instead of assuming.

ALTER TABLE payments
    -- OUR id for the refund, and the idempotency key Cashfree dedups on.
    -- Derived from the payment rather than random, so a retry after a timeout
    -- reaches the SAME refund instead of sending the money a second time.
    -- That is the whole safety property of this table change.
    ADD COLUMN IF NOT EXISTS refund_id VARCHAR(64),

    -- Cashfree's own id for it, for reconciling against their dashboard.
    ADD COLUMN IF NOT EXISTS provider_refund_id VARCHAR(64),

    -- What was actually sent back. Separate from amount because a partial
    -- refund is a real thing a shop does, even though today it is always the
    -- full amount - storing it now means the day partials arrive, the history
    -- is already correct.
    ADD COLUMN IF NOT EXISTS refund_amount NUMERIC(12, 2),

    -- Set only when the provider confirms SUCCESS, or when a shopkeeper
    -- records handing cash back. Never set at the moment of asking.
    ADD COLUMN IF NOT EXISTS refunded_at TIMESTAMP,

    -- Why the provider refused, shown to the shopkeeper so they can act.
    ADD COLUMN IF NOT EXISTS refund_failure_reason VARCHAR(255),

    -- CASH or GATEWAY. A COD refund never touches Cashfree and must not be
    -- reconciled against it; without this the two are indistinguishable
    -- afterwards and a reconciliation job would flag every cash refund as
    -- missing at the provider.
    ADD COLUMN IF NOT EXISTS refund_channel VARCHAR(16);

-- One refund id, once. Belt to the braces of deriving it deterministically:
-- if a code path ever generates a colliding id, the insert fails rather than
-- quietly attaching a second refund to the same key.
CREATE UNIQUE INDEX IF NOT EXISTS ux_payments_refund_id
    ON payments (refund_id)
    WHERE refund_id IS NOT NULL;

-- The reconciliation query - "which refunds did we ask for and never see
-- land" - filters on exactly this.
CREATE INDEX IF NOT EXISTS ix_payments_refund_in_flight
    ON payments (payment_status)
    WHERE refund_id IS NOT NULL AND refunded_at IS NULL;
