# Live checks

These two files are **not** run by `flutter test`. They live outside `test/`
because they need a real backend on the other end, and a suite that fails when
nothing is listening is a suite people learn to ignore.

Every other Flutter test in this repo answers the app's requests with JSON the
test itself wrote, which proves the parsing matches what somebody *believed*
the backend sends. These ask the backend.

- `live_contract_test.dart` — do the app's models parse what the server
  actually returns, for each of the four roles.
- `marketplace_wiring_test.dart` — does the **provider graph the screens
  watch** reach the backend: discovery, shop selection, catalogue, cart,
  checkout, order group, merchant earnings, the platform console's writes,
  the rider's round, and the tenant refusals.

## Running them

The staff accounts cannot be created through a customer API, and should not be
- there is no self-service route for "make me a platform admin". Register them
normally, then grant the role directly:

```bash
S=$(date +%s)
BASE=http://localhost:8088/v1

reg() { curl -s -X POST $BASE/api/auth/register -H 'Content-Type: application/json' -d "$1" -o /dev/null -w "%{http_code}\n"; }
reg "{\"name\":\"Check platform\",\"email\":\"check-plat-$S@example.test\",\"phone\":\"81$RANDOM$RANDOM\",\"password\":\"LiveCheck!2345\"}"
reg "{\"name\":\"Check merchant\",\"email\":\"check-merch-$S@example.test\",\"phone\":\"82$RANDOM$RANDOM\",\"password\":\"LiveCheck!2345\"}"

psql "$DB" \
  -c "UPDATE customers SET role='PLATFORM_ADMIN' WHERE email='check-plat-$S@example.test';" \
  -c "UPDATE customers SET role='ADMIN'          WHERE email='check-merch-$S@example.test';" \
  -c "INSERT INTO shop_staff (shop_id, customer_id, is_default, active)
      SELECT 1, id, true, true FROM customers WHERE email='check-merch-$S@example.test';"

# A rider signs in against delivery_partners, not customers. Reuse a bcrypt
# hash from an account whose password you know rather than minting one.
HASH=$(psql "$DB" -tAc "select password from customers where email='check-plat-$S@example.test';")
psql "$DB" -c "INSERT INTO delivery_partners
   (name, mobile, vehicle_type, available, active, login_email, password_hash, shop_id)
   VALUES ('Check rider $S', '84$S', 'BIKE', false, true, 'check-rider-$S@gmail.com', '$HASH', 1);"

# A variant Shop #1 lists AND has stock of. Hardcoding one means the checkout
# check starts failing the moment an earlier run has bought the shelf out.
VARIANT=$(psql "$DB" -tAc "select pv.id from product_variants pv
   join shop_product_variants spv on spv.product_variant_id=pv.id and spv.shop_id=1 and spv.available
   join inventory i on i.product_variant_id=pv.id and i.shop_id=1
   join products p on p.id=pv.product_id
   where i.stock>100 and pv.available and p.active order by i.stock desc limit 1;")

flutter test integration_check \
  --dart-define=API_BASE_URL=$BASE \
  --dart-define=LIVE_ACCOUNT_STAMP=$S \
  --dart-define=LIVE_VARIANT_ID=$VARIANT
```

## Afterwards

The checks place a real order and register real accounts. The platform check
creates its own merchant and shop rather than touching an existing one, and
leaves the merchant REMOVED. Clean the rest up with:

```bash
psql "$DB" \
  -c "UPDATE delivery_partners SET available=false, active=false, deleted_at=now(),
      login_email=null, password_hash=null WHERE login_email LIKE 'check-rider-%@gmail.com';" \
  -c "DELETE FROM shop_staff WHERE customer_id IN
      (SELECT id FROM customers WHERE email LIKE 'check-%@example.test');"
```

A rider row is retired rather than deleted: auto-assignment picks the
least-loaded available rider, and a leftover idle one will quietly collect
every delivery the backend test suite creates on its next run.
