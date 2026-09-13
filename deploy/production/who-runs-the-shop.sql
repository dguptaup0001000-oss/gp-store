-- Who holds which role, and can they reach their own shop?
--
-- WHY THIS EXISTS. "I cannot find Merchants & Shops in the admin app" has
-- exactly two causes and they look identical from the phone: the account is
-- an ADMIN rather than a SUPER_ADMIN, or the installed APK predates the
-- release where SUPER_ADMIN gained the cross-shop permission. Guessing cost
-- three rounds. This answers the first half from the database.
--
-- NO EMAIL, NO PHONE, NO NAME LEAVES HERE. This output lands in an Actions
-- log GitHub keeps for ninety days. The owner needs to recognise their own
-- account, not read everyone's contact details, so the email is masked to
-- its first two characters and its domain - enough to point at yourself,
-- useless to anybody else. Same rule the money alert follows.
--
-- READ ONLY. Every statement is a SELECT. Nothing here updates, inserts or
-- deletes, which is why it is safe to run against production on a whim.

\echo '--- staff accounts by role ---'
SELECT
    role                                   AS role,
    count(*)                               AS accounts
  FROM customers
 WHERE role IS NOT NULL
   AND role NOT IN ('CUSTOMER', 'DELIVERY_BOY')
 GROUP BY role
 ORDER BY role;

\echo '--- each staff account, masked, and the shops it can work in ---'
SELECT
    c.id                                   AS id,
    -- First two characters and the domain. 'dg****@gmail.com'
    CASE
      WHEN c.email IS NULL OR position('@' in c.email) = 0 THEN '(no email)'
      ELSE left(split_part(c.email, '@', 1), 2) || '****@'
           || split_part(c.email, '@', 2)
    END                                    AS masked_email,
    c.role                                 AS role,
    c.active                               AS active,
    -- THE HALF THAT MATTERS FOR AN ADMIN. A shop owner with no shop_staff
    -- row resolves to no shop at all, so their console is empty for a
    -- completely different reason than a missing permission.
    coalesce(
      (SELECT count(*) FROM shop_staff s
        WHERE s.customer_id = c.id AND s.active IS TRUE), 0)
                                           AS shops_on_staff_of,
    coalesce(
      (SELECT count(*) FROM shop_staff s
        WHERE s.customer_id = c.id AND s.active IS TRUE
          AND s.is_default IS TRUE), 0)    AS has_home_shop
  FROM customers c
 WHERE c.role IS NOT NULL
   AND c.role NOT IN ('CUSTOMER', 'DELIVERY_BOY')
 ORDER BY c.role, c.id;

\echo '--- merchants and shops that exist ---'
SELECT
    (SELECT count(*) FROM merchants)                        AS merchants,
    (SELECT count(*) FROM merchants WHERE status = 'ACTIVE') AS merchants_active,
    (SELECT count(*) FROM shops)                            AS shops,
    (SELECT count(*) FROM shops WHERE status = 'ACTIVE')     AS shops_active;

\echo '--- every merchant: who owns it, and may it hold a shop ---'
SELECT
    m.id                                   AS id,
    m.display_name                         AS trading_as,
    m.status                               AS status,
    m.owner_customer_id                    AS owner_id,
    m.is_demo                              AS demo
  FROM merchants m
 ORDER BY m.id;

\echo '--- every shop: the code is unique, so this is what a new one collides with ---'
SELECT
    s.id                                   AS id,
    s.code                                 AS code,
    s.display_name                         AS shop_name,
    s.status                               AS status,
    s.merchant_id                          AS merchant,
    s.active                               AS active,
    (s.latitude IS NOT NULL
     AND s.longitude IS NOT NULL)          AS has_pin,
    s.max_delivery_radius_km               AS radius_km
  FROM shops s
 ORDER BY s.id;

\echo '--- id sequences vs the biggest id actually in the table ---'
-- A SEQUENCE BEHIND ITS TABLE IS THE FAULT THAT LOOKS LIKE A CONFLICT.
-- Every id in this application comes from the database, so if rows were ever
-- put in with explicit ids - a restored dump, a seed file, SQL typed into a
-- console - the sequence never moved and the next row it hands out is one
-- that is already taken. Opening a shop then fails on a key collision that
-- reads to the person on the phone as "that already exists".
SELECT
    t.name                                 AS table_name,
    t.max_id                               AS biggest_id_in_table,
    t.next_val                             AS sequence_is_at,
    (t.next_val <= t.max_id)               AS sequence_is_behind
  FROM (
    SELECT 'shops' AS name,
           (SELECT coalesce(max(id), 0) FROM shops) AS max_id,
           (SELECT last_value + CASE WHEN is_called THEN 1 ELSE 0 END
              FROM shops_id_seq) AS next_val
    UNION ALL
    SELECT 'shop_staff',
           (SELECT coalesce(max(id), 0) FROM shop_staff),
           (SELECT last_value + CASE WHEN is_called THEN 1 ELSE 0 END
              FROM shop_staff_id_seq)
    UNION ALL
    SELECT 'merchants',
           (SELECT coalesce(max(id), 0) FROM merchants),
           (SELECT last_value + CASE WHEN is_called THEN 1 ELSE 0 END
              FROM merchants_id_seq)
    UNION ALL
    SELECT 'store_operations_settings',
           (SELECT coalesce(max(id), 0) FROM store_operations_settings),
           (SELECT last_value + CASE WHEN is_called THEN 1 ELSE 0 END
              FROM store_operations_settings_id_seq)
    UNION ALL
    SELECT 'delivery_pricing_settings',
           (SELECT coalesce(max(id), 0) FROM delivery_pricing_settings),
           (SELECT last_value + CASE WHEN is_called THEN 1 ELSE 0 END
              FROM delivery_pricing_settings_id_seq)
  ) t
 ORDER BY t.name;

\echo '--- the per-shop singleton rows, which a new shop gets a pair of ---'
SELECT
    s.id                                   AS shop_id,
    s.code                                 AS code,
    (SELECT count(*) FROM store_operations_settings o
      WHERE o.shop_id = s.id)              AS operations_rows,
    (SELECT count(*) FROM delivery_pricing_settings p
      WHERE p.shop_id = s.id)              AS pricing_rows,
    (SELECT count(*) FROM shop_staff st
      WHERE st.shop_id = s.id AND st.active IS TRUE) AS staff_rows
  FROM shops s
 ORDER BY s.id;

\echo '--- every unique index on the tables opening a shop writes to ---'
-- WHY THIS IS WORTH ASKING. A database that has been migrated for a year is
-- not the same shape as one built from the migrations this morning, and an
-- index left behind by an older release is invisible from the application:
-- nothing in the code knows it is there, so nothing pre-checks it, and the
-- refusal arrives as the generic "that already exists".
--
-- Compare this list against a freshly built schema. Anything here that is
-- not there is the answer.
SELECT
    t.relname                              AS table_name,
    i.relname                              AS index_name,
    pg_get_indexdef(ix.indexrelid)         AS definition
  FROM pg_index ix
  JOIN pg_class i ON i.oid = ix.indexrelid
  JOIN pg_class t ON t.oid = ix.indrelid
 WHERE ix.indisunique
   AND t.relname IN ('shops', 'shop_staff', 'merchants',
                     'store_operations_settings', 'delivery_pricing_settings')
 ORDER BY t.relname, i.relname;
