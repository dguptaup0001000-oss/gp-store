-- The worker's login lives on the worker record.
--
-- WHY THIS EXISTS. A delivery worker used to sign in through the CUSTOMERS
-- table: the roster row pointed at a customer account, and that account's
-- password was the worker's password. That coupling produced a bug the shop
-- could not get out of. The owner's Gmail is a staff account, so setting a
-- worker password on it was a privilege escalation and had to be refused -
-- which left a one-person shop unable to put itself on its own roster, with
-- the worker app saying only "this login is not linked to a worker record".
--
-- Worker credentials are now their own thing. Signing in to the worker app
-- reads THIS table and nothing else: no customer row, no role, no account
-- link. The same address can therefore be an administrator, a shopper and a
-- rider at once, because those are three separate credentials that cannot
-- collide.
--
-- account_customer_id is deliberately left in place and left alone. Existing
-- rows still carry it and DeliveryService reads it to find a rider's device
-- token for push. It is no longer consulted for authentication.

ALTER TABLE delivery_partners
    -- The address the worker types in. Mandatory for anyone who signs in,
    -- but nullable in the column: roster rows created before this migration
    -- have no login yet, and a NOT NULL would refuse to apply against them.
    ADD COLUMN IF NOT EXISTS login_email VARCHAR(255),

    -- BCrypt output, never the password. Same encoder as customer
    -- registration, so the hash format is one thing and not two.
    ADD COLUMN IF NOT EXISTS password_hash VARCHAR(255),

    -- Set to a moment in the future to bar this worker until then; NULL, or
    -- any instant in the past, means they may sign in. A timestamp rather
    -- than a boolean because "closed for an hour" has to end BY ITSELF -
    -- a flag someone must remember to clear is a worker locked out all
    -- weekend because the person who set it went home.
    ADD COLUMN IF NOT EXISTS suspended_until TIMESTAMP,

    -- Why they are suspended, shown to them at the login screen. Being told
    -- "you cannot sign in" without a reason is how a worker calls the shop.
    ADD COLUMN IF NOT EXISTS suspension_reason VARCHAR(255),

    -- Soft delete. Deliveries reference this row, so removing it outright
    -- would leave finished orders with no rider and break the shop's own
    -- records. Set this and the worker vanishes from the roster, from
    -- dispatch, and from the login screen, while history stays readable.
    ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMP;

-- Both identifiers are unique, because both are accepted at the login screen
-- and an identifier matching two workers has no correct answer.
--
-- PARTIAL, on purpose. Rows with no login (created before this, or staff who
-- only appear on the roster) all have NULL, and a plain UNIQUE would be
-- satisfied by that in Postgres but would still index every row for nothing.
-- The WHERE also lets a deleted worker's address be handed to somebody else,
-- which is what a shop expects after someone leaves.
--
-- LOWER() because people type their address however they like, and the login
-- lookup matches case-insensitively - without this the index would not serve
-- that query and two rows differing only in case could both exist.
CREATE UNIQUE INDEX IF NOT EXISTS ux_delivery_partners_login_email
    ON delivery_partners (LOWER(login_email))
    WHERE login_email IS NOT NULL AND deleted_at IS NULL;

CREATE UNIQUE INDEX IF NOT EXISTS ux_delivery_partners_mobile_login
    ON delivery_partners (mobile)
    WHERE mobile IS NOT NULL AND deleted_at IS NULL;

-- The roster list, dispatch and every worker lookup all filter on "not
-- deleted". Without this they degrade to a full scan as the table grows.
CREATE INDEX IF NOT EXISTS ix_delivery_partners_live
    ON delivery_partners (deleted_at)
    WHERE deleted_at IS NULL;
