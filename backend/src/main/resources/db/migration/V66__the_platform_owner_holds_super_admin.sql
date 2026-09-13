-- The platform owner holds SUPER_ADMIN.
--
-- WHAT THIS FIXES. The marketplace had no platform owner at all. The
-- "Who runs the shop" workflow asked production and got one staff account
-- back: id 1, role ADMIN, on Shop #1's staff. Zero SUPER_ADMIN rows. So
-- nobody could open Merchants & Shops, nobody could register a merchant,
-- and the Super Admin APK correctly refused the only account there was.
--
-- WHY A MIGRATION AND NOT A PSQL SESSION. Every other lasting change in this
-- project is versioned, reviewed and applied by the deploy. A role granted by
-- hand on the box is a change with no author, no date and no diff - and this
-- is the single most privileged row in the database. V65 set a column on
-- customers the same way; this sets a value in one.
--
-- WHY IT CANNOT MINT A SECOND PLATFORM OWNER. PlatformStaffService refuses to
-- open a SUPER_ADMIN account deliberately: a second platform owner is meant
-- to be a considered act, not an API call. This migration keeps that promise
-- from the other direction - the NOT EXISTS guard means it does nothing at
-- all once any account already holds the role. Re-running it is a no-op, and
-- it can never quietly add a second.
--
-- SAFE IN EVERY OTHER ENVIRONMENT. A fresh CI database has no customers when
-- Flyway runs, and no developer machine has this address, so all three
-- conditions fail and nothing is updated. That is also the failure mode if
-- the address is wrong: a silent no-op, not a wrong account promoted - which
-- is why the deploy is followed by re-running the diagnostic rather than
-- trusting this file.
--
-- TO UNDO: UPDATE customers SET role = 'ADMIN' WHERE role = 'SUPER_ADMIN';
-- The account keeps its shop_staff row throughout, so demoting it returns it
-- to being Shop #1's owner exactly as before.
--
-- WHAT IT CHANGES FOR THAT ACCOUNT, stated plainly because it is not nothing:
-- TenantResolver.resolve() returns the platform-wide scope for anyone holding
-- PERM_PLATFORM_ADMIN, and it does so BEFORE it looks at their home shop. So
-- this account's shop screens stop being scoped to Shop #1 and start spanning
-- the marketplace. With one shop that is the same data. With a second
-- merchant it is not, and the account will need to name a shop (X-Shop-Id)
-- to work inside one. That trade was put to the owner and accepted.
UPDATE customers
   SET role = 'SUPER_ADMIN'
 WHERE role = 'ADMIN'
   AND email IS NOT NULL
   AND lower(email) = lower('dguptaup0001000@gmail.com')
   AND NOT EXISTS (
         SELECT 1 FROM customers existing
          WHERE existing.role IN ('SUPER_ADMIN', 'PLATFORM_ADMIN')
       );
