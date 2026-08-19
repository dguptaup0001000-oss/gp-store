-- Phase 10: durable outbox for business-critical post-order work.
--
-- Post-order side effects (invoice generation, delivery auto-assignment)
-- were handed to an in-memory ThreadPoolExecutor. That executor is bounded
-- and applies proper backpressure, so it was never a memory risk - but it is
-- not DURABLE. Anything still queued or mid-flight when the JVM stops is
-- gone silently.
--
-- On this deployment that is not a rare event: Render redeploys the service
-- on every push to main, and the free tier spins the instance down after
-- ~15 minutes of no traffic. An order placed seconds before either could
-- keep the customer's money and its inventory decrement while permanently
-- losing its invoice - a GST/accounting record the business is required to
-- have, with nothing anywhere reporting that it went missing.
--
-- The row is INSERTed inside the same transaction as the order itself, so it
-- commits with the order or does not exist at all. A worker drains it
-- afterwards, retrying with backoff until it succeeds.
CREATE TABLE IF NOT EXISTS outbox_events (
    id              BIGSERIAL PRIMARY KEY,
    aggregate_type  VARCHAR(64)  NOT NULL,
    aggregate_id    BIGINT       NOT NULL,
    event_type      VARCHAR(64)  NOT NULL,
    status          VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    attempts        INTEGER      NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP    NOT NULL,
    last_error      VARCHAR(1000),
    created_at      TIMESTAMP    NOT NULL,
    processed_at    TIMESTAMP
);

-- The worker's only hot query: "PENDING rows whose next_attempt_at has
-- passed, oldest first". Partial on status so the index stays small - once
-- rows are PROCESSED they are irrelevant to this query, and they are the
-- overwhelming majority over time.
CREATE INDEX IF NOT EXISTS idx_outbox_pending_due
    ON outbox_events (next_attempt_at, id)
    WHERE status = 'PENDING';

-- Used by the purge job (PROCESSED rows past their retention) and by any
-- "what is stuck?" query looking for FAILED ones.
CREATE INDEX IF NOT EXISTS idx_outbox_status_processed_at
    ON outbox_events (status, processed_at);
