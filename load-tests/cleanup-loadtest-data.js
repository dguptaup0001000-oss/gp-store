// Opt-in cleanup of records EXPLICITLY marked as load-test data.
//
// Deletes only:
//   - customers whose email matches loadtest_*@example.com
//   - their carts, addresses, and orders
//
// Never deletes any other customer. Dry-run by default.
//
//   DRY_RUN=0 node cleanup-loadtest-data.js

import { execFileSync } from 'node:child_process';

const DRY_RUN = process.env.DRY_RUN !== '0';

const sql = `
BEGIN;
SELECT count(*) AS loadtest_customers
FROM customers
WHERE email ILIKE 'loadtest_%@example.com';

SELECT count(*) AS loadtest_orders
FROM orders o
JOIN customers c ON c.id = o.customer_id
WHERE c.email ILIKE 'loadtest_%@example.com';

DO $$
BEGIN
  IF current_setting('gpstore.cleanup_commit', true) = '1' THEN
    DELETE FROM order_items
    WHERE order_id IN (
      SELECT o.id FROM orders o
      JOIN customers c ON c.id = o.customer_id
      WHERE c.email ILIKE 'loadtest_%@example.com'
    );
    DELETE FROM orders
    WHERE customer_id IN (SELECT id FROM customers WHERE email ILIKE 'loadtest_%@example.com');
    DELETE FROM cart_items
    WHERE cart_id IN (
      SELECT ct.id FROM carts ct
      JOIN customers c ON c.id = ct.customer_id
      WHERE c.email ILIKE 'loadtest_%@example.com'
    );
    DELETE FROM carts
    WHERE customer_id IN (SELECT id FROM customers WHERE email ILIKE 'loadtest_%@example.com');
    DELETE FROM addresses
    WHERE customer_id IN (SELECT id FROM customers WHERE email ILIKE 'loadtest_%@example.com');
    -- customers themselves are kept unless GPSTORE_DELETE_CUSTOMERS=1 so
    -- deterministic loadtest_vu_* logins keep working.
  END IF;
END $$;
COMMIT;
`;

function main() {
  if (DRY_RUN) {
    console.log('DRY RUN. Showing load-test row counts only. Set DRY_RUN=0 to delete orders/carts/addresses for loadtest_* emails.');
  }
  const env = {
    ...process.env,
    PGPASSWORD: process.env.PGPASSWORD || 'gpstore_test_password',
    PGOPTIONS: DRY_RUN ? '' : '-c gpstore.cleanup_commit=1',
  };
  const out = execFileSync(
    'psql',
    ['-h', process.env.PGHOST || 'localhost', '-U', process.env.PGUSER || 'gpstore',
     '-d', process.env.PGDATABASE || 'gpstore_test', '-v', 'ON_ERROR_STOP=1', '-c', sql],
    { env, encoding: 'utf8' },
  );
  console.log(out);
  if (!DRY_RUN) {
    console.log('Deleted orders/carts/addresses for loadtest_*@example.com only. Customers kept.');
  }
}

main();
