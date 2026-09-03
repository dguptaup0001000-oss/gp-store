-- A customer can hand something back, and the shop can say yes or no.
--
-- WHAT THE SHOP DOES TODAY WITHOUT THIS. The customer rings. The shopkeeper
-- writes the item on a pad, or remembers it. If they agree to take it back
-- they open the admin screen and type a refund amount they worked out in
-- their head from the order total. Nothing records WHICH item came back, so
-- nothing can answer "how much of what we sell comes back", the stock count
-- stays wrong until someone notices, and the refund amount is a number a
-- tired person calculated at a counter.
--
-- WHAT THIS ADDS. The request is a row: these items, this many, because of
-- this. The shop approves or rejects it. Approval computes the refund from
-- the ORDER'S OWN LINE PRICES rather than from anything the customer sent,
-- issues it through the refunds ledger V41 added, and puts the stock back.
--
-- WHY THE MONEY IS NOT IN THIS TABLE. A return is a request about goods; a
-- refund is money leaving the shop. Keeping them separate means a return can
-- be approved and its refund still be in flight, refused by the provider, or
-- retried - all of which happen - without the return's own record changing
-- to describe something that did not occur. The link is refund_id, set when
-- the refund is actually opened.

CREATE TABLE IF NOT EXISTS order_returns (
    id              BIGSERIAL PRIMARY KEY,
    order_id        BIGINT NOT NULL REFERENCES orders (id),

    -- Denormalised from the order on purpose. Every query here is "this
    -- customer's returns" or "returns awaiting a decision", and joining
    -- through orders for the first one buys nothing.
    customer_id     BIGINT NOT NULL REFERENCES customers (id),

    -- REQUESTED, APPROVED, REJECTED, CANCELLED.
    status          VARCHAR(16) NOT NULL,

    -- The customer's own words. Free text, not a fixed list: a shop's real
    -- reasons do not fit an enum, and an enum would grow an OTHER value that
    -- means nothing and is chosen half the time.
    reason          VARCHAR(500),

    -- The shopkeeper's answer, shown to the customer when it is a refusal.
    -- A rejection with no reason is how a customer decides the shop is
    -- dishonest, so the service refuses to record one.
    decision_note   VARCHAR(500),

    -- Computed server-side from the order's line prices at approval, never
    -- taken from the request. Null until approved.
    refund_amount   NUMERIC(12, 2),

    -- The refunds row this created, if any. Null for a rejection, and null
    -- for an approval on an order that was never paid online.
    refund_id       VARCHAR(64),

    requested_at    TIMESTAMP NOT NULL,
    decided_at      TIMESTAMP,

    -- Which staff account decided. A returns decision is money, so it is
    -- attributable; the audit log carries the same fact.
    decided_by      BIGINT REFERENCES customers (id),

    created_at      TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS order_return_items (
    id               BIGSERIAL PRIMARY KEY,
    order_return_id  BIGINT NOT NULL REFERENCES order_returns (id) ON DELETE CASCADE,

    -- The LINE, not the product. Two lines can carry the same variant at
    -- different prices - a coupon applied to one, a price change between
    -- orders - and refunding "the product" would pick one of them at random.
    order_item_id    BIGINT NOT NULL REFERENCES order_items (id),

    quantity         INTEGER NOT NULL CHECK (quantity > 0),

    -- What this line's units were actually charged at, copied at approval so
    -- the return's record survives a later price change on the order.
    unit_price       NUMERIC(12, 2) NOT NULL CHECK (unit_price >= 0),

    created_at       TIMESTAMP NOT NULL DEFAULT NOW()
);

-- ONE LINE AT MOST ONCE PER RETURN. Without this a client could send the
-- same order item twice in one request and be refunded for it twice, and the
-- per-line "how much of this line is already returned" check would not catch
-- it because both rows arrive in the same transaction.
CREATE UNIQUE INDEX IF NOT EXISTS ux_order_return_items_line
    ON order_return_items (order_return_id, order_item_id);

-- "This customer's returns", newest first - the customer app's only query.
CREATE INDEX IF NOT EXISTS ix_order_returns_customer
    ON order_returns (customer_id, requested_at DESC);

-- "What is waiting for me", the admin queue. Partial, so it stays the size
-- of the queue rather than the size of the shop's whole returns history.
CREATE INDEX IF NOT EXISTS ix_order_returns_pending
    ON order_returns (requested_at)
    WHERE status = 'REQUESTED';

-- Every return on an order. Used to work out how much of each line has
-- already gone back before allowing another request against it.
CREATE INDEX IF NOT EXISTS ix_order_returns_order ON order_returns (order_id);

CREATE INDEX IF NOT EXISTS ix_order_return_items_return
    ON order_return_items (order_return_id);
