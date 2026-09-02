-- What the money alert is allowed to know.
--
-- AGGREGATES ONLY, AND ON PURPOSE. Every column below is a count or a
-- duration. There is no customer name, no email, no phone, no order number
-- and no per-order amount, because the output of this query lands in a
-- GitHub Actions log that is kept for ninety days and is readable by anyone
-- with access to the repository. A stuck refund is somebody's money and
-- their identity has no business being there.
--
-- The payment ids needed to actually FIX a stuck refund are in the shop's
-- own admin screens and in the application log on the box. This query exists
-- to make a person go and look, not to do the looking for them.
--
-- Emits the same key=value shape as the backup sidecar's status.txt so the
-- two alerts read alike.

SELECT 'refunds_in_flight=' || COUNT(*)
FROM payments
WHERE refund_id IS NOT NULL AND refunded_at IS NULL;

-- Past the outside of a normal bank settlement. Kept equal to the backend's
-- refund.stuck-after-hours default; the alert script re-states the same
-- number so a drift between them is visible in the alert text itself.
SELECT 'refunds_stuck=' || COUNT(*)
FROM payments
WHERE refund_id IS NOT NULL
  AND refunded_at IS NULL
  AND refund_requested_at IS NOT NULL
  AND refund_requested_at < NOW() - INTERVAL '72 hours';

-- Whole hours, floored. Zero when nothing is in flight, which reads
-- correctly rather than as a missing value.
SELECT 'oldest_refund_hours=' || COALESCE(
    FLOOR(EXTRACT(EPOCH FROM (NOW() - MIN(refund_requested_at))) / 3600)::bigint, 0)
FROM payments
WHERE refund_id IS NOT NULL AND refunded_at IS NULL;

-- A refund the PROVIDER REJECTED. Worse than a slow one: it is not coming
-- back on its own and the customer is still owed. The reconciliation writes
-- the reason onto the row; this is what makes somebody read it.
SELECT 'refunds_failed_unread=' || COUNT(*)
FROM payments
WHERE refund_id IS NOT NULL
  AND refunded_at IS NULL
  AND refund_failure_reason IS NOT NULL;

SELECT 'collected_at=' || TO_CHAR(NOW() AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"');
