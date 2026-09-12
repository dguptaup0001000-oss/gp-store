-- A password somebody else chose is a password that has to be replaced.
--
-- WHY THIS COLUMN EXISTS. Until now no API could make an account an ADMIN:
-- the only role ever assigned in code was DELIVERY_BOY, and
-- CreateCustomerCannotEscalateTest deliberately ignores a role in a
-- create-customer body, with the reason written into its own assertion -
-- "that account's password was chosen in the same request, so it is a login
-- as that role". The consequence was that onboarding a real merchant needed
-- direct SQL on the box, which is why scripts/verify/onboard_second_shop.sh
-- demands an account that already exists and refuses to make one.
--
-- The platform owner can now open a merchant's login, and this column is
-- what keeps that from becoming a shared credential: the account arrives
-- with a one-time password and can do NOTHING but replace it. After that
-- only the merchant knows it, so their actions are their own in a dispute.
--
-- NULLABLE, DEFAULT FALSE, AND READ AS FALSE WHEN NULL. Every row that
-- predates this column chose its own password at registration. A NOT NULL
-- column with no default would fail the migration on a live table; a
-- default of true would lock every existing account out of the app on the
-- next deploy.
ALTER TABLE customers
    ADD COLUMN IF NOT EXISTS must_change_password BOOLEAN DEFAULT FALSE;

-- Existing rows explicitly, rather than relying on the default reaching
-- them: ADD COLUMN ... DEFAULT backfills in modern PostgreSQL, but saying
-- so makes the intent survive a reader who is not sure which version this
-- ran on.
UPDATE customers SET must_change_password = FALSE WHERE must_change_password IS NULL;
