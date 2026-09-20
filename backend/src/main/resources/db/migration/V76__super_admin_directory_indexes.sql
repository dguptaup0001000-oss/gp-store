-- Indexes the Super Admin merchant/customer directory search needs.
--
-- WHY TRIGRAM AND NOT B-TREE. The directory matches partially and
-- case-insensitively - an operator types "deep" and expects Deepak Phone
-- Shop - so every predicate is lower(col) LIKE '%term%'. A leading wildcard
-- makes an ordinary b-tree useless: Postgres cannot seek into the middle of a
-- string, so it seq-scans. At a hundred customers nobody notices; at a
-- million, every keystroke reads the whole table. GIN over trigrams is the
-- index family that can actually serve that predicate.
--
-- V69 ALREADY COVERED the columns the global search used: customer name,
-- email and phone; merchant legal and display name; shop display name; worker
-- name. This adds only what the new per-section search reaches and V69 did
-- not: a merchant's own contact details and contact name, and a shop's code
-- and business name. Nothing here duplicates an existing index.
--
-- THE OPERATOR CLASS IS RESOLVED, NOT ASSUMED, and this is not defensive
-- programming for its own sake - it is V28's lesson written down. Production
-- is a Supabase dump where pg_trgm lives in schema "extensions", so the
-- unqualified name gin_trgm_ops does not resolve there and a migration that
-- hardcodes it fails the deploy. V28 exists solely because V27 learned that
-- the hard way. This uses the same resolution V28 settled on.
--
-- Do not CREATE EXTENSION pg_trgm here. V5 already does, and repeating it
-- would fail the deploy where the role may not create extensions. If the
-- extension is somehow absent the block notices and returns: a missing index
-- makes search slow, while a failed migration makes the release not ship.
DO $$
DECLARE
    opclass text;
    target  record;
BEGIN
    SELECT format('%I.%I', n.nspname, oc.opcname)
      INTO opclass
    FROM pg_opclass oc
    JOIN pg_am am ON am.oid = oc.opcmethod
    JOIN pg_namespace n ON n.oid = oc.opcnamespace
    WHERE oc.opcname = 'gin_trgm_ops'
      AND am.amname = 'gin'
    ORDER BY CASE n.nspname WHEN 'extensions' THEN 0 ELSE 1 END
    LIMIT 1;

    IF opclass IS NULL THEN
        RAISE NOTICE 'gin_trgm_ops not found; skipping Super Admin directory indexes';
        RETURN;
    END IF;

    FOR target IN
        SELECT * FROM (VALUES
            ('idx_merchants_contact_email_trgm', 'merchants', 'contact_email'),
            ('idx_merchants_contact_phone_trgm', 'merchants', 'contact_phone'),
            ('idx_merchants_contact_name_trgm',  'merchants', 'contact_name'),
            ('idx_shops_code_trgm',              'shops',     'code'),
            ('idx_shops_business_name_trgm',     'shops',     'business_name')
        ) AS t(index_name, table_name, column_name)
    LOOP
        -- Skipped rather than failed when a column is not present: this
        -- migration must not be the reason a deploy stops.
        IF NOT EXISTS (
            SELECT 1 FROM information_schema.columns
            WHERE table_name = target.table_name
              AND column_name = target.column_name
        ) THEN
            RAISE NOTICE 'Skipping %: %.% not present',
                target.index_name, target.table_name, target.column_name;
            CONTINUE;
        END IF;

        EXECUTE format(
            'CREATE INDEX IF NOT EXISTS %I ON %I USING gin (lower(%I) %s)',
            target.index_name, target.table_name, target.column_name, opclass);
    END LOOP;
END
$$;

-- ORDER HISTORY ON A CUSTOMER 360 reads one customer's orders newest first,
-- and the "where does this person buy" breakdown groups those same rows by
-- shop. Both are the same access path, so one composite index serves them.
CREATE INDEX IF NOT EXISTS idx_orders_customer_time
    ON orders (customer_id, order_date DESC);

-- The per-shop breakdown on a Merchant 360 groups a merchant's orders by
-- shop. Orders carry shop_id, so this is the shape that avoids a seq scan
-- once one merchant has years of them.
CREATE INDEX IF NOT EXISTS idx_orders_shop_time
    ON orders (shop_id, order_date DESC);
