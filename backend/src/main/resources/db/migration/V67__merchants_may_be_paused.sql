-- A merchant may be PAUSED, which is not a milder suspension.
--
-- WHY A MIGRATION AT ALL. merchants.status carries a Hibernate-generated
-- check constraint (merchants_status_check) listing the enum values that
-- existed when the table was first built. Adding a value to the Java enum
-- alone leaves every UPDATE to the new value failing at the database with a
-- constraint violation - which is how this was found, in a test rather than
-- in production.
--
-- WHY PAUSED IS NOT SUSPENDED. A pause is a business state: a shutter down
-- for a festival, a kitchen being rebuilt, a merchant who asked for a month
-- off. A suspension is an enforcement action the platform took against them.
-- Recording one as the other puts an accusation in a merchant's permanent
-- record for closing over Diwali - and that record is what an appeal is
-- argued from later.
--
-- SAFE AND REPEATABLE. Nothing is deleted and no row is rewritten: the
-- constraint is replaced by a strictly wider one, so every value that was
-- legal before is still legal. A database that somehow already allows PAUSED
-- ends in the same state.

ALTER TABLE merchants DROP CONSTRAINT IF EXISTS merchants_status_check;

ALTER TABLE merchants ADD CONSTRAINT merchants_status_check
    CHECK (status IN (
        'APPLICATION',
        'PENDING_REVIEW',
        'VERIFICATION_REQUIRED',
        'APPROVED',
        'ACTIVE',
        'PAUSED',
        'SUSPENDED',
        'REJECTED',
        'REMOVED'
    ));
