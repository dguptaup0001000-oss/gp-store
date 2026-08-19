-- Phase 4: request fingerprint for idempotency keys.
--
-- An idempotency key previously only answered "has this key been used
-- before". That makes a retried checkout and a key REUSED for a different
-- checkout indistinguishable, so reusing one silently replayed the first
-- order and the customer never received the second, with nothing anywhere
-- reporting a problem.
--
-- Storing a SHA-256 of the checkout's canonical form (customer, address,
-- payment method, coupon, and the sorted cart lines - see
-- OrderService.computeRequestFingerprint) lets those two cases be told
-- apart: same key + same request replays, same key + different request is
-- rejected with 409.
--
-- Nullable on purpose. Rows written before this column existed have nothing
-- to compare against; the code treats a null fingerprint as "unverifiable"
-- and replays rather than rejecting, so keys already in flight when this
-- deploys keep working instead of failing real customers mid-checkout.
ALTER TABLE idempotency_records
    ADD COLUMN IF NOT EXISTS request_fingerprint VARCHAR(64);

-- Phase 5: retention.
--
-- idempotency_records gained a row per checkout attempt and nothing ever
-- removed them, so the table grew for the life of the deployment. The
-- cleanup job deletes in bounded batches ordered by id, filtering on
-- created_at; without this index each batch is a sequential scan of an
-- ever-growing table, which is what makes naive cleanup jobs slower than
-- the growth they are meant to contain.
CREATE INDEX IF NOT EXISTS idx_idempotency_created_at
    ON idempotency_records (created_at);

-- Phase 16: indexes matching the actual hot-path predicates.
--
-- The stale-payment expiry sweep filters on
-- (payment_status, payment_method, payment_date) and orders by id. V2's
-- idx_payments_payment_status covers only the first column, leaving the
-- method/date filtering to be done row by row.
--
-- Lookup by order_id is NOT indexed again here: V4's uq_payments_order_id
-- unique index already serves it.
CREATE INDEX IF NOT EXISTS idx_payments_status_method_date
    ON payments (payment_status, payment_method, payment_date);

-- The customer feed reads "my notifications, newest first". V2 indexed
-- customer_id alone, so the sort had to happen per request rather than
-- being read straight off the index.
CREATE INDEX IF NOT EXISTS idx_notifications_customer_sent
    ON notifications (customer_id, sent_at DESC);

-- Partial on purpose: the unread-count query and the mark-all-read UPDATE
-- both only ever touch unread rows, and read notifications become the
-- overwhelming majority over time. Indexing only unread ones keeps this
-- roughly constant-sized instead of growing with the customer's history.
CREATE INDEX IF NOT EXISTS idx_notifications_customer_unread
    ON notifications (customer_id)
    WHERE is_read = FALSE;

-- Notifications by order already has idx_notifications_order_id from V2 -
-- deliberately not recreated.

-- Remove two indexes that are now strictly redundant. Every index costs
-- write time and storage on every insert/update to its table, so a
-- redundant one is a permanent tax with no read benefit.
--
-- idx_notifications_customer_id (V2): fully covered by the leftmost prefix
-- of idx_notifications_customer_sent created above - Postgres uses a
-- composite index for queries filtering on its first column alone.
DROP INDEX IF EXISTS idx_notifications_customer_id;

-- idx_payments_order_id (V2): fully covered by uq_payments_order_id (V4),
-- a unique index on that exact column. V2 predates V4, which is how both
-- ended up existing.
DROP INDEX IF EXISTS idx_payments_order_id;
