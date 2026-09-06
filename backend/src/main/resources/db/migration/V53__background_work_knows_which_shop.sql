-- V53: an outbox event says which shop it is acting for.
--
-- THE BUG THIS FIXES IS TOTAL, AND IT WAS INVISIBLE UNDER ONE SHOP.
--
-- Order placement writes an ORDER_PLACED row to outbox_events inside the
-- checkout transaction - deliberately, because generating an invoice and
-- assigning a rider must survive a redeploy. OutboxWorker then drains it on a
-- background thread, and that thread has NO TENANT SCOPE.
--
-- Under SINGLE_SHOP that never showed: TenantDefaults answers Shop #1 when
-- nothing says otherwise, so the invoice was stamped correctly by accident.
-- Under MULTI_SHOP_PRODUCTION there is no single shop to fall back to, so
-- every ORDER_PLACED event fails with "Refusing to insert an Invoice with no
-- shop" and retries until it dead-letters. Which means, for every order in the
-- marketplace: no invoice, and no rider. The order is taken, the money is
-- taken, and nothing goes out.
--
-- Found by walking a second merchant's first order end to end. No isolation
-- test could have found it, because nothing leaked - the work simply never
-- happened.
--
-- WHY THE COLUMN AND NOT A LOOKUP. The worker could load the order and read
-- its shop, and for an order-shaped event that would work. But the event is
-- the durable record of "this work is owed", and which shop it is owed to is
-- part of that record: it is known for certain at write time, inside the
-- transaction that created the aggregate, and re-deriving it later means
-- every new event type has to remember to. Writing it down once is the same
-- decision as stamping shop_id on every other row.
--
-- IT IS NOT A TENANT BOUNDARY, and outbox_events is deliberately not a
-- filtered entity. The drain is platform-wide by design - one worker serves
-- every shop - so a filter here would mean each shop needing its own worker.
-- The column says which shop to ACT FOR, not who may read the row. Same
-- distinction as cart_items, and recorded in ShopScopeIsNotOptionalTest.

ALTER TABLE outbox_events ADD COLUMN IF NOT EXISTS shop_id BIGINT REFERENCES shops (id);

-- Pending work first: the index the drain actually uses stays as it is, and
-- this one is only for the backfill check and for support queries asking
-- "what is stuck for this merchant".
CREATE INDEX IF NOT EXISTS idx_outbox_events_shop ON outbox_events (shop_id);

-- ------------------------------------------------------------------ BACKFILL
--
-- Every event so far is about an order, and an order knows its shop.
DO $$
DECLARE
    v_shop_id BIGINT;
    v_orders  BIGINT;
    v_rest    BIGINT;
BEGIN
    UPDATE outbox_events e
       SET shop_id = o.shop_id
      FROM orders o
     WHERE e.shop_id IS NULL
       AND e.aggregate_type = 'Order'
       AND o.id = e.aggregate_id;
    GET DIAGNOSTICS v_orders = ROW_COUNT;

    -- Anything left is an event whose aggregate no longer exists, or a type
    -- that predates this column. Shop #1 is the only shop those can have come
    -- from - the column exists precisely because a second shop is new.
    SELECT id INTO v_shop_id FROM shops ORDER BY id LIMIT 1;
    IF v_shop_id IS NULL THEN
        RAISE EXCEPTION 'V53 cannot run before a first shop exists (V46 creates it)';
    END IF;

    UPDATE outbox_events SET shop_id = v_shop_id WHERE shop_id IS NULL;
    GET DIAGNOSTICS v_rest = ROW_COUNT;

    RAISE NOTICE 'V53: % event(s) took their order''s shop, % fell back to Shop #1',
        v_orders, v_rest;
END $$;

-- ------------------------------------------------------------------- VERIFY
DO $$
DECLARE
    unscoped BIGINT;
    crossed  BIGINT;
BEGIN
    SELECT count(*) INTO unscoped FROM outbox_events WHERE shop_id IS NULL;
    IF unscoped > 0 THEN
        RAISE EXCEPTION 'V53 left % outbox event(s) with no shop to act for', unscoped;
    END IF;

    -- An order event filed under a different shop than its own order would
    -- send that shop's rider to another merchant's customer.
    SELECT count(*) INTO crossed
    FROM outbox_events e JOIN orders o ON o.id = e.aggregate_id
    WHERE e.aggregate_type = 'Order' AND e.shop_id IS DISTINCT FROM o.shop_id;

    IF crossed > 0 THEN
        RAISE EXCEPTION 'V53: % outbox event(s) name a different shop than their own order',
            crossed;
    END IF;
END $$;
