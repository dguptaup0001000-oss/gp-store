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
