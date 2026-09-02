-- When did we ask for this refund?
--
-- WHY THIS COLUMN IS WORTH A MIGRATION. V37 gave refunds a refund_id and a
-- refunded_at, which answers "did it land". It cannot answer "how long has
-- it been in the air", and that is the question a stuck refund is made of.
-- A refund Cashfree accepted as PENDING and never settled looks exactly like
-- one sent thirty seconds ago; without a request timestamp, a reconciliation
-- job can only re-ask about all of them forever and can never say "this one
-- has been stuck for four days and needs a human".
--
-- updated_at was the alternative and it is the wrong clock: any write to the
-- payment row moves it, so an unrelated status touch would silently reset a
-- stuck refund's age back to zero - which is precisely the case the job
-- exists to catch.
--
-- NULLABLE ON PURPOSE. Refunds already in flight when this deploys have no
-- honest value to backfill; NOW() would claim they were requested at deploy
-- time, which is a fabricated fact in the payment record. The reconciliation
-- treats a null request time as "age unknown, check it anyway", which is the
-- safe reading.

ALTER TABLE payments
    ADD COLUMN IF NOT EXISTS refund_requested_at TIMESTAMP;

-- The reconciliation's own query: gateway refunds asked for and not yet
-- landed, oldest first. Partial, so it stays small - it indexes only the
-- handful of refunds actually in flight, never the whole payments table.
CREATE INDEX IF NOT EXISTS ix_payments_refund_awaiting_provider
    ON payments (refund_requested_at)
    WHERE refund_id IS NOT NULL AND refunded_at IS NULL;
