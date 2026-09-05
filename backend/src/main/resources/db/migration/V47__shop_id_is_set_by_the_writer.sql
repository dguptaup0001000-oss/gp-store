-- V47: the shop on a new row comes from the request, not from a column default.
--
-- WHAT V46 LEFT BEHIND, DELIBERATELY. V46 backfilled every shop-owned table and
-- then set "DEFAULT <shop #1>" on each shop_id, because at that point nothing in
-- the application knew how to set the column. Without the default, every insert
-- between that migration and this one would have written a row with no shop -
-- invisible to every shop-scoped query, which is an order a customer placed and
-- a shopkeeper can never see. The comment there said the default had to come out
-- in the slice that taught the writers. This is that slice.
--
-- WHY IT MUST COME OUT. A default is indistinguishable from an answer. Once a
-- second merchant is trading, an insert that forgets the shop does not fail - it
-- files that merchant's order under Shop #1, quietly, and the first anyone hears
-- of it is a shopkeeper reading a stranger's delivery address. Removing the
-- default converts that silent mis-filing into a NULL, and the application's
-- own listener (TenantEntityListener) refuses to produce one: it stamps the shop
-- from the tenant scope on the thread, and throws in multi-shop mode when there
-- is no scope to stamp from.
--
-- TWO TABLES KEEP THEIR DEFAULT ON PURPOSE. store_operations_settings and
-- delivery_pricing_settings are single-row settings tables read by
-- findById(SINGLETON_ID). A Hibernate filter does not apply to a load by primary
-- key, so making them shop-owned is not a matter of tagging the entity - it needs
-- the singleton itself to become one row per shop, which is its own change with
-- its own migration. Until then the honest state is: still single-shop, still
-- defaulted, and named here so it is a decision rather than an oversight.

ALTER TABLE orders                    ALTER COLUMN shop_id DROP DEFAULT;
ALTER TABLE payments                  ALTER COLUMN shop_id DROP DEFAULT;
ALTER TABLE deliveries                ALTER COLUMN shop_id DROP DEFAULT;
ALTER TABLE delivery_batches          ALTER COLUMN shop_id DROP DEFAULT;
ALTER TABLE delivery_partners         ALTER COLUMN shop_id DROP DEFAULT;
ALTER TABLE invoices                  ALTER COLUMN shop_id DROP DEFAULT;
ALTER TABLE order_returns             ALTER COLUMN shop_id DROP DEFAULT;
ALTER TABLE coupons                   ALTER COLUMN shop_id DROP DEFAULT;
ALTER TABLE inventory                 ALTER COLUMN shop_id DROP DEFAULT;
ALTER TABLE catalog_import_runs       ALTER COLUMN shop_id DROP DEFAULT;
ALTER TABLE order_scan_events         ALTER COLUMN shop_id DROP DEFAULT;
ALTER TABLE customer_delivery_ratings ALTER COLUMN shop_id DROP DEFAULT;

-- NO INDEXES HERE. V46 already created one per shop-owned table
-- (idx_orders_shop_id and friends). A second index on the same column under a
-- different name costs a write on every insert and buys nothing.

-- VERIFY (§92: a command that returned 0 is not the same as data that is right).
DO $$
DECLARE
    t          TEXT;
    stillThere TEXT := '';
    orphans    BIGINT;
BEGIN
    FOREACH t IN ARRAY ARRAY[
        'orders','payments','deliveries','delivery_batches','delivery_partners',
        'invoices','order_returns','coupons','inventory',
        'catalog_import_runs','order_scan_events','customer_delivery_ratings'
    ]
    LOOP
        IF EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_schema = current_schema()
                     AND table_name = t
                     AND column_name = 'shop_id'
                     AND column_default IS NOT NULL) THEN
            stillThere := stillThere || t || ' ';
        END IF;

        EXECUTE format('SELECT count(*) FROM %I WHERE shop_id IS NULL', t) INTO orphans;
        IF orphans > 0 THEN
            RAISE EXCEPTION 'V47 found % rows with no shop in %s - V46''s backfill did not hold',
                orphans, t;
        END IF;
    END LOOP;

    IF stillThere <> '' THEN
        RAISE EXCEPTION 'V47 failed to remove the shop_id default from: %', stillThere;
    END IF;
END $$;
