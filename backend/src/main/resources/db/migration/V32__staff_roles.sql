-- Staff roles: let customers.role hold the six new staff values.
--
-- WHY THIS MIGRATION EXISTS AT ALL. Hibernate generates a CHECK constraint
-- for an @Enumerated(STRING) column listing exactly the enum values it knew
-- about when it created the table:
--
--   CHECK (role IN ('CUSTOMER', 'ADMIN', 'DELIVERY_BOY'))
--
-- Production runs ddl-auto=validate, and validate does NOT inspect check
-- constraints. So adding values to the Role enum without this file starts
-- cleanly, passes every read, and then rejects the first attempt to save a
-- MANAGER with a constraint violation - at runtime, in front of whoever was
-- promoting them. The enum and this migration are a matched pair.
--
-- DROPPED BY DISCOVERY, NOT BY NAME. The constraint is Hibernate-generated,
-- so its name depends on which Hibernate version created the table and is not
-- guaranteed to be customers_role_check on every environment. This finds any
-- check constraint on customers that mentions the role column and drops it,
-- which is correct whether the name matches, differs, or the constraint was
-- never created at all.
DO $$
DECLARE
    constraint_name text;
BEGIN
    FOR constraint_name IN
        SELECT con.conname
        FROM pg_constraint con
        JOIN pg_class rel ON rel.oid = con.conrelid
        JOIN pg_namespace nsp ON nsp.oid = rel.relnamespace
        WHERE rel.relname = 'customers'
          AND nsp.nspname = current_schema()
          AND con.contype = 'c'
          AND pg_get_constraintdef(con.oid) ILIKE '%role%'
    LOOP
        EXECUTE format('ALTER TABLE customers DROP CONSTRAINT %I', constraint_name);
    END LOOP;
END $$;

-- Recreated with every value the Role enum now declares.
--
-- The constraint is kept rather than dropped outright: it is what stops a
-- typo'd or injected role string from ever reaching the column, and a row
-- whose role this build cannot parse would fail closed at login with no
-- permissions - a confusing outage rather than a clean rejection at write
-- time.
ALTER TABLE customers
    ADD CONSTRAINT customers_role_check
    CHECK (role IN (
        'CUSTOMER',
        'ADMIN',
        'DELIVERY_BOY',
        'SUPER_ADMIN',
        'MANAGER',
        'INVENTORY_MANAGER',
        'ORDER_MANAGER',
        'DELIVERY_MANAGER',
        'SUPPORT'
    ));

-- NO DATA CHANGE. Every existing row keeps the role it has, and ADMIN keeps
-- every permission (see RolePermissions). Nobody is promoted or demoted by
-- this migration; it only widens what the column will accept.
