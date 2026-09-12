-- TWO SHOPS AND SOMETHING TO BUY FROM EACH, FOR A TEST DATABASE.
--
-- WHY THIS EXISTS. Every marketplace behaviour worth testing by hand needs at
-- least two shops that both reach one address and price the same item
-- differently - discovery, Best Deal, the final-payable comparison, preferred
-- shops, a basket that splits, two orders, two payments. A database with one
-- shop in it cannot show any of them, and a tester who has to build the
-- fixture by hand builds a slightly different one every time.
--
-- WHERE IT MAY RUN. A throwaway or staging database ONLY. It refuses to run
-- against a database whose name looks like production, and it refuses to run
-- against one that already holds orders or payments - because seeded rows
-- mixed into real trading data are almost impossible to pick back out.
--
-- THE DATA IS OBVIOUSLY FAKE, on purpose. Every name says TEST, every phone
-- number is in the 9999xxxxxx block, and every row is flagged is_demo or
-- is_test_data where the column exists. Nobody should be able to look at a
-- screen and wonder whether a row is real.
--
-- WHAT IT DELIBERATELY DOES NOT DO. It creates no payments, no orders and no
-- money of any kind: those are what you are meant to create by hand through
-- the app, and pre-seeding them would test the seeder rather than the
-- checkout. It sets no commercial amounts - no commission, no weekly fee, no
-- cancellation percentage - because those are undecided and a fixture is not
-- the place to invent one.
--
--   psql -d gpstore_testbed -f scripts/verify/seed_two_shop_testbed.sql
--
-- Shop A: near, cheap delivery, has everything.
-- Shop B: farther, dearer delivery, CHEAPER on the dal, and OUT OF STOCK of
--         the rice - so the out-of-stock rule and the "farther shop is worth
--         it" rule both have something to act on.

\set ON_ERROR_STOP on

DO $$
DECLARE
    db text := current_database();
    existing_orders bigint;
    existing_payments bigint;
BEGIN
    IF db ILIKE '%prod%' OR db = 'gpstore' THEN
        RAISE EXCEPTION
            'Refusing to seed test data into "%" - the name looks like production. '
            'Point this at a throwaway or staging database.', db;
    END IF;

    SELECT count(*) INTO existing_orders   FROM orders;
    SELECT count(*) INTO existing_payments FROM payments;
    IF existing_orders > 0 OR existing_payments > 0 THEN
        RAISE EXCEPTION
            'Refusing to seed: "%" already holds % order(s) and % payment(s). '
            'Seeded rows mixed into real trading data cannot be picked back out.',
            db, existing_orders, existing_payments;
    END IF;
END $$;

-- ---------------------------------------------------------------- merchants
INSERT INTO merchants (legal_name, status, active, is_demo)
VALUES ('TEST Merchant A (seeded)', 'ACTIVE', TRUE, TRUE),
       ('TEST Merchant B (seeded)', 'ACTIVE', TRUE, TRUE)
ON CONFLICT DO NOTHING;

-- -------------------------------------------------------------------- shops
--
-- BOTH REACH THE SAME PIN, which is the whole point: one shop 1.2 km away and
-- one 4.6 km away, both with a radius that covers the customer, so discovery
-- returns two and the customer has a choice to make.
INSERT INTO shops (merchant_id, code, display_name, business_name, status,
                   verification_level, latitude, longitude,
                   max_delivery_radius_km, support_phone, time_zone,
                   is_demo, active, created_at, updated_at)
SELECT m.id, 'TEST-SHOP-A', 'TEST Shop A (seeded)', 'TEST A Kirana Stores',
       'ACTIVE', 'VERIFIED', 27.16231, 83.940468, 8.00, '9999000001',
       'Asia/Kolkata', TRUE, TRUE, now(), now()
FROM merchants m WHERE m.legal_name = 'TEST Merchant A (seeded)'
ON CONFLICT (code) DO NOTHING;

INSERT INTO shops (merchant_id, code, display_name, business_name, status,
                   verification_level, latitude, longitude,
                   max_delivery_radius_km, support_phone, time_zone,
                   is_demo, active, created_at, updated_at)
SELECT m.id, 'TEST-SHOP-B', 'TEST Shop B (seeded)', 'TEST B Provision Mart',
       'ACTIVE', 'NONE', 27.20000, 83.980000, 8.00, '9999000002',
       'Asia/Kolkata', TRUE, TRUE, now(), now()
FROM merchants m WHERE m.legal_name = 'TEST Merchant B (seeded)'
ON CONFLICT (code) DO NOTHING;

-- ------------------------------------------------------------------- hours
--
-- Open every day, so a tester is never blocked by the clock. Closing a shop
-- is its own test and is done through the merchant screen, not here.
INSERT INTO shop_business_hours (shop_id, day_of_week, opens_at, closes_at,
                                 created_at, updated_at)
SELECT s.id, d, TIME '06:00', TIME '23:00', now(), now()
FROM shops s CROSS JOIN generate_series(1, 7) AS d
WHERE s.code IN ('TEST-SHOP-A', 'TEST-SHOP-B')
ON CONFLICT DO NOTHING;

-- --------------------------------------------------------------- catalogue
--
-- ONE CENTRAL CATALOGUE, PRICED PER SHOP. The products and their variants are
-- the platform's; what each shop charges and whether it is holding any are
-- the shop's, which is the split the whole marketplace rests on.
INSERT INTO products (name, brand, active, bestseller, featured,
                      is_private_product, is_test_data, price_verified,
                      created_at, updated_at)
VALUES ('TEST Toor Dal 1kg', 'TEST Brand', TRUE, FALSE, FALSE, FALSE, TRUE, FALSE, now(), now()),
       ('TEST Sona Masoori Rice 5kg', 'TEST Brand', TRUE, FALSE, FALSE, FALSE, TRUE, FALSE, now(), now())
ON CONFLICT DO NOTHING;

INSERT INTO product_variants (product_id, quantity, unit, mrp, selling_price,
                              available, display_order, sku)
SELECT p.id, 1, 'kg', 120.00, 100.00, TRUE, 1, 'TEST-DAL-1KG'
FROM products p WHERE p.name = 'TEST Toor Dal 1kg'
ON CONFLICT DO NOTHING;

INSERT INTO product_variants (product_id, quantity, unit, mrp, selling_price,
                              available, display_order, sku)
SELECT p.id, 5, 'kg', 400.00, 360.00, TRUE, 1, 'TEST-RICE-5KG'
FROM products p WHERE p.name = 'TEST Sona Masoori Rice 5kg'
ON CONFLICT DO NOTHING;

-- ------------------------------------------------- what each shop charges
--
-- Shop B undercuts Shop A on the dal. With Shop B's dearer delivery that is
-- what makes the FINAL PAYABLE comparison interesting rather than obvious:
-- whichever shop wins, it is not the one with the lower shelf price alone.
INSERT INTO shop_product_variants (shop_id, product_variant_id, selling_price,
                                   mrp, available, display_order, active,
                                   created_at, updated_at)
SELECT s.id, v.id, 100.00, 120.00, TRUE, 1, TRUE, now(), now()
FROM shops s, product_variants v
WHERE s.code = 'TEST-SHOP-A' AND v.sku = 'TEST-DAL-1KG'
ON CONFLICT DO NOTHING;

INSERT INTO shop_product_variants (shop_id, product_variant_id, selling_price,
                                   mrp, available, display_order, active,
                                   created_at, updated_at)
SELECT s.id, v.id, 360.00, 400.00, TRUE, 1, TRUE, now(), now()
FROM shops s, product_variants v
WHERE s.code = 'TEST-SHOP-A' AND v.sku = 'TEST-RICE-5KG'
ON CONFLICT DO NOTHING;

INSERT INTO shop_product_variants (shop_id, product_variant_id, selling_price,
                                   mrp, available, display_order, active,
                                   created_at, updated_at)
SELECT s.id, v.id, 88.00, 120.00, TRUE, 1, TRUE, now(), now()
FROM shops s, product_variants v
WHERE s.code = 'TEST-SHOP-B' AND v.sku = 'TEST-DAL-1KG'
ON CONFLICT DO NOTHING;

-- LISTED BUT NOT HELD, which is the case the out-of-stock rule exists for:
-- the card must stay on Shop B's shelf, say so, show NO price, and refuse the
-- Add. A variant simply absent from the shop would prove nothing.
INSERT INTO shop_product_variants (shop_id, product_variant_id, selling_price,
                                   mrp, available, display_order, active,
                                   created_at, updated_at)
SELECT s.id, v.id, 375.00, 400.00, TRUE, 2, TRUE, now(), now()
FROM shops s, product_variants v
WHERE s.code = 'TEST-SHOP-B' AND v.sku = 'TEST-RICE-5KG'
ON CONFLICT DO NOTHING;

-- ----------------------------------------------------------------- stock
INSERT INTO inventory (shop_id, product_variant_id, stock, reserved_stock,
                       minimum_stock, maximum_stock)
SELECT s.id, v.id, 50, 0, 5, 200
FROM shops s, product_variants v
WHERE s.code = 'TEST-SHOP-A' AND v.sku IN ('TEST-DAL-1KG', 'TEST-RICE-5KG')
ON CONFLICT DO NOTHING;

INSERT INTO inventory (shop_id, product_variant_id, stock, reserved_stock,
                       minimum_stock, maximum_stock)
SELECT s.id, v.id, 50, 0, 5, 200
FROM shops s, product_variants v
WHERE s.code = 'TEST-SHOP-B' AND v.sku = 'TEST-DAL-1KG'
ON CONFLICT DO NOTHING;

-- Zero, not absent. Shop B lists the rice and has run out of it.
INSERT INTO inventory (shop_id, product_variant_id, stock, reserved_stock,
                       minimum_stock, maximum_stock)
SELECT s.id, v.id, 0, 0, 5, 200
FROM shops s, product_variants v
WHERE s.code = 'TEST-SHOP-B' AND v.sku = 'TEST-RICE-5KG'
ON CONFLICT DO NOTHING;

-- ----------------------------------------------------------------- report
DO $$
DECLARE
    shop_count bigint;
    listing_count bigint;
    out_of_stock bigint;
BEGIN
    SELECT count(*) INTO shop_count FROM shops WHERE code LIKE 'TEST-SHOP-%';
    SELECT count(*) INTO listing_count FROM shop_product_variants spv
      JOIN shops s ON s.id = spv.shop_id WHERE s.code LIKE 'TEST-SHOP-%';
    SELECT count(*) INTO out_of_stock FROM inventory i
      JOIN shops s ON s.id = i.shop_id
     WHERE s.code LIKE 'TEST-SHOP-%' AND i.stock = 0;

    IF shop_count <> 2 THEN
        RAISE EXCEPTION 'Expected 2 test shops, found %', shop_count;
    END IF;
    IF out_of_stock < 1 THEN
        RAISE EXCEPTION
            'No out-of-stock listing was seeded, so the rule that matters most '
            'has nothing to act on';
    END IF;

    RAISE NOTICE 'Seeded % test shops, % shop listings, % of them out of stock.',
        shop_count, listing_count, out_of_stock;
    RAISE NOTICE 'Customers, workers and orders are NOT seeded - register them '
                 'through the app, which is what you are testing.';
END $$;
