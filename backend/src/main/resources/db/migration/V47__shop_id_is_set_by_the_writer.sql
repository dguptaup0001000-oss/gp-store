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

-- An index per shop-owned table, because every read is now "... and shop_id = ?".
-- A marketplace where each shop's query scans every shop's rows gets slower for
-- everybody with each merchant that joins, which is the opposite of the point.
CREATE INDEX IF NOT EXISTS idx_orders_shop                    ON orders (shop_id);
CREATE INDEX IF NOT EXISTS idx_payments_shop                  ON payments (shop_id);
CREATE INDEX IF NOT EXISTS idx_deliveries_shop                ON deliveries (shop_id);
CREATE INDEX IF NOT EXISTS idx_delivery_batches_shop          ON delivery_batches (shop_id);
CREATE INDEX IF NOT EXISTS idx_delivery_partners_shop         ON delivery_partners (shop_id);
CREATE INDEX IF NOT EXISTS idx_invoices_shop                  ON invoices (shop_id);
CREATE INDEX IF NOT EXISTS idx_order_returns_shop             ON order_returns (shop_id);
CREATE INDEX IF NOT EXISTS idx_coupons_shop                   ON coupons (shop_id);
CREATE INDEX IF NOT EXISTS idx_inventory_shop                 ON inventory (shop_id);
CREATE INDEX IF NOT EXISTS idx_catalog_import_runs_shop       ON catalog_import_runs (shop_id);
CREATE INDEX IF NOT EXISTS idx_order_scan_events_shop         ON order_scan_events (shop_id);
CREATE INDEX IF NOT EXISTS idx_customer_delivery_ratings_shop ON customer_delivery_ratings (shop_id);

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
