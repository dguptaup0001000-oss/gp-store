-- Lets order_item INSERTs actually batch.
--
-- The problem, measured rather than assumed: a 10-item checkout issued TEN
-- separate INSERT statements into order_items, even though
-- hibernate.jdbc.batch_size=50 and order_inserts=true are both configured.
-- Those settings were doing nothing here, because OrderItem used
-- GenerationType.IDENTITY.
--
-- Hibernate cannot batch inserts for an IDENTITY-generated entity. It has to
-- execute each INSERT immediately to read back the generated key, so the
-- JDBC batch is flushed one row at a time. This is a documented Hibernate
-- limitation, not a configuration mistake - the configuration simply never
-- applied to this entity.
--
-- Why it is worth fixing: those ten round trips happen INSIDE the checkout
-- transaction, while the per-variant inventory row locks are held. Every one
-- of them extends the window during which another customer buying the same
-- product is blocked. On a local socket that is invisible; against a managed
-- database across a network it is ten times the per-query latency, paid in
-- the most contended section of the whole system.
--
-- Switching to a sequence lets Hibernate pre-allocate ids and send the
-- inserts as one batch.
--
-- SAFETY - the only real risk here is a primary key collision with rows that
-- already exist, so the start value is DERIVED from the live table rather
-- than hardcoded. A literal START WITH would be correct on an empty database
-- and catastrophic on a populated one.
--
-- The +1000 margin covers rows inserted between this statement and the
-- application picking up the new strategy on restart.
DO $$
DECLARE
    next_id BIGINT;
BEGIN
    SELECT COALESCE(MAX(id), 0) + 1000 INTO next_id FROM order_items;

    -- INCREMENT BY must match OrderItem's allocationSize exactly. If the two
    -- disagree, Hibernate hands out ids the database has not reserved and
    -- they eventually collide - so these two numbers are a matched pair and
    -- must be changed together or not at all.
    EXECUTE format(
        'CREATE SEQUENCE IF NOT EXISTS order_items_seq START WITH %s INCREMENT BY 50',
        next_id);
END
$$;
