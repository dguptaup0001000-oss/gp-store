-- V57: the states §1 names that the order table could not express, and the
-- fact every one of them turns on - WHO ended it, and WHOSE FAULT it was.
--
-- WHAT THE LIFECYCLE COULD NOT SAY. An order was PENDING_CONFIRMATION,
-- CONFIRMED, PACKING, PACKED, READY_TO_DISPATCH, OUT_FOR_DELIVERY, DELIVERED
-- or CANCELLED. That leaves three of Part 3's states unrepresentable - a
-- merchant REFUSING an order before accepting it, a delivery that FAILED and
-- came back, and an order that is finally FINISHED - and it leaves CANCELLED
-- meaning four different things at once.
--
-- WHY "cancelled_by" AND NOT "CUSTOMER_CANCELLED" / "MERCHANT_CANCELLED".
-- Splitting CANCELLED into three states would be the tidy-looking change that
-- silently breaks the application: every report, every dashboard count, every
-- index, every screen and every test asks `order_status = 'CANCELLED'`, and
-- all of them would quietly start missing two thirds of the cancellations.
-- Recording WHO beside the state answers everything Part 3 asks - who
-- cancelled, whether the merchant rejected it, whether the customer is to be
-- charged (§10/§12) - and keeps every existing query correct (§19).
--
-- FAULT IS SEPARATE FROM WHO, and that separation is the whole of §12. A
-- customer cancelling because the shop rang to say the atta never arrived was
-- cancelled BY the customer and is not their FAULT, and charging them a
-- cancellation fee for the shop's stock-out is the failure §12 exists to
-- prevent. One column cannot say both.

-- ------------------------------------------------- 1. the three new states
--
-- Discovered rather than assumed, and recreated with every value: the same
-- dance V20 documents, for the same reason. A constraint named by guess would
-- be left in place by a DROP that matched nothing, and the failure would show
-- up as "violates check constraint" on the first real order.
DO $$
DECLARE
    constraint_name TEXT;
BEGIN
    FOR constraint_name IN
        SELECT con.conname
        FROM pg_constraint con
        JOIN pg_class rel ON rel.oid = con.conrelid
        WHERE rel.relname = 'orders'
          AND con.contype = 'c'
          AND pg_get_constraintdef(con.oid) ILIKE '%order_status%'
    LOOP
        EXECUTE format('ALTER TABLE orders DROP CONSTRAINT %I', constraint_name);
    END LOOP;
END $$;

ALTER TABLE orders ADD CONSTRAINT orders_order_status_check
    CHECK (order_status IS NULL OR order_status IN (
        'PENDING_CONFIRMATION',
        'CONFIRMED',
        'PACKING',
        'PACKED',
        'READY_TO_DISPATCH',
        'OUT_FOR_DELIVERY',
        'DELIVERED',
        'CANCELLED',
        -- The merchant refused it before taking it on. Reachable ONLY from
        -- PENDING_CONFIRMATION: once a shop has accepted, §2 says it owes the
        -- order, and "reject" would be a back door out of that promise.
        'REJECTED',
        -- The round came back with the packet still in it. NOT terminal - the
        -- shop can send it out again tomorrow, which is what usually happens.
        'DELIVERY_FAILED',
        -- Finished: delivered, settled, past returning. §1's last step.
        'COMPLETED'
    ));

-- ------------------------------------------------------ 2. how it ended
ALTER TABLE orders ADD COLUMN IF NOT EXISTS ended_by     VARCHAR(20);
ALTER TABLE orders ADD COLUMN IF NOT EXISTS ended_reason VARCHAR(500);
ALTER TABLE orders ADD COLUMN IF NOT EXISTS ended_at     TIMESTAMP;

-- CUSTOMER / MERCHANT / NOBODY. "NOBODY" is a real answer and not a null:
-- an order cancelled because a flood closed the road is nobody's fault, and
-- that is different from not having decided yet (which is what null means).
ALTER TABLE orders ADD COLUMN IF NOT EXISTS fault        VARCHAR(20);

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_orders_ended_by') THEN
        ALTER TABLE orders ADD CONSTRAINT ck_orders_ended_by
            CHECK (ended_by IS NULL OR ended_by IN
                   ('CUSTOMER', 'MERCHANT', 'WORKER', 'PLATFORM', 'SYSTEM'));
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_orders_fault') THEN
        ALTER TABLE orders ADD CONSTRAINT ck_orders_fault
            CHECK (fault IS NULL OR fault IN ('CUSTOMER', 'MERCHANT', 'NOBODY'));
    END IF;
END $$;

-- NOT BACKFILLED. Orders cancelled before this column existed were cancelled
-- by somebody nobody wrote down, and stamping them all 'CUSTOMER' would be
-- inventing a fact - one that §10's charge logic would then read as licence
-- to bill people for cancellations they may not have made. Null reads as
-- "not recorded", which is true.

CREATE INDEX IF NOT EXISTS idx_orders_ended_by ON orders (ended_by) WHERE ended_by IS NOT NULL;

-- ------------------------------------------------------------------ VERIFY
DO $$
DECLARE
    n BIGINT;
BEGIN
    SELECT count(*) INTO n FROM orders WHERE ended_by IS NOT NULL;
    IF n > 0 THEN
        RAISE EXCEPTION 'V57: % order(s) were backfilled with an ender nobody recorded. §10 '
                        'charges customers for their own cancellations; inventing who made '
                        'them is inventing a bill.', n;
    END IF;

    -- The three new states have to be accepted, or the lifecycle cannot use
    -- them and the first rejection fails at the database.
    BEGIN
        PERFORM 1 FROM orders WHERE order_status = 'REJECTED';
    EXCEPTION WHEN others THEN
        RAISE EXCEPTION 'V57: order_status does not accept REJECTED';
    END;
END $$;
