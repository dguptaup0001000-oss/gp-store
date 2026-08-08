-- Prevents duplicate payment rows for the same order under concurrent
-- initiatePayment() requests (double-tap, client retry-on-timeout, etc).
-- The application layer already checks-then-creates, but that check has
-- a race window; this constraint is the real backstop - Postgres will
-- reject the second insert outright rather than relying on app logic
-- alone. Uses a unique index (not a table constraint) so it can be
-- added without a table rewrite - CREATE UNIQUE INDEX IF NOT EXISTS is
-- safe and fast at this table's current size.
CREATE UNIQUE INDEX IF NOT EXISTS uq_payments_order_id ON payments (order_id);
