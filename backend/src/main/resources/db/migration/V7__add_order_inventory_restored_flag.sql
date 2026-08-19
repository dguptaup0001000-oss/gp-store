-- Exactly-once guard for returning an order's reserved stock.
--
-- Three paths could each decide to give an order's stock back - an explicit
-- cancellation, the stale-UPI expiry scheduler, and payment failure/refund
-- handling - and they coordinated only through order/payment status, which
-- does not actually answer "has this stock already gone back".
--
-- The concrete race: cancelOrder() set a COD_PENDING payment to FAILED and a
-- SUCCESS payment to REFUND_PENDING, but left a PENDING UPI payment
-- untouched. So a cancelled order (whose stock had already been restored)
-- still had a PENDING UPI payment with an old payment_date, which the expiry
-- sweep then picked up and restored the stock for a SECOND time - silently
-- inflating inventory with no error anywhere.
--
-- Every restore path now checks and sets this flag while holding the order
-- row lock, so whichever path gets there first wins and the others no-op.
ALTER TABLE orders
    ADD COLUMN IF NOT EXISTS inventory_restored BOOLEAN NOT NULL DEFAULT FALSE;

-- Backfill: orders already in a terminal cancelled state have, by
-- definition, already had their stock returned by the pre-existing
-- cancelOrder() path. Leaving them at the FALSE default would make them
-- eligible for a restore the first time any path examined them after this
-- deploy - re-introducing on old rows the exact double-restore this column
-- exists to prevent.
UPDATE orders
SET inventory_restored = TRUE
WHERE order_status = 'CANCELLED';
