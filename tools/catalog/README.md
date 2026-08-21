# GP-Store test catalog

Roughly a thousand realistic Indian kirana products for **development and
testing**. Everything in it is assumed data.

> **Nothing here has been verified.** Prices were not read from a shelf, a
> website or an API — they are plausible Indian retail rates computed from a
> per-product base rate. Every seeded product carries `is_test_data = true`
> and `price_verified = false`, and both must be reviewed before launch.

## Running it

The seeder is an admin endpoint, so it works from a phone. All routes need an
ADMIN token (enforced in `SecurityConfig`).

```bash
# 1. Seed / refresh the catalogue. Safe to run as many times as you like.
curl -X POST https://gp-store.onrender.com/api/admin/catalog/seed \
     -H "Authorization: Bearer $ADMIN_TOKEN"

# 2. Fetch real product images from Open Food Facts, in batches.
#    Repeat until "considered" comes back smaller than the limit.
curl -X POST "https://gp-store.onrender.com/api/admin/catalog/images/backfill?limit=100" \
     -H "Authorization: Bearer $ADMIN_TOKEN"

# 3. Check the catalogue's health at any time.
curl https://gp-store.onrender.com/api/admin/catalog/audit \
     -H "Authorization: Bearer $ADMIN_TOKEN"

# 4. Before launch: remove every test product.
curl -X DELETE "https://gp-store.onrender.com/api/admin/catalog/test-data?confirm=true" \
     -H "Authorization: Bearer $ADMIN_TOKEN"
```

### Why the image step is separate

The machine that generated this catalogue could not reach
`openfoodfacts.org` — the network policy refuses the connection. The two
honest options were to invent plausible-looking URLs or to leave the field
empty and fetch them from somewhere with working network.

Inventing them is worse than empty. A fabricated URL makes the catalogue look
populated in the database and then 404s on a customer's phone, where nobody is
reading a log. So the seeder writes **no images at all**, and the backfill runs
from the deployed backend, storing only URLs it has confirmed resolve.

## Changing the catalogue

Edit `catalog_spec.py` — it is a list of real
`(category, subcategory, brand, product, packs, base rate)` tuples — then:

```bash
python3 tools/catalog/generate_catalog.py
```

That rewrites `backend/src/main/resources/catalog/gp-store-test-catalog.json`.
Redeploy and re-run the seed endpoint; existing products are updated in place.

Output is deterministic: the random choices (stock level, discount) are seeded
from each product's own SKU, so re-running produces an identical file, and
**inserting** a product does not reshuffle every product after it. A generator
whose output churns on every edit cannot be reviewed in a diff.

## What re-running does and does not touch

| | On first insert | On a re-run |
|---|---|---|
| Name, brand, category, description, keywords, flags | written | **updated** |
| MRP, selling price, pack size | written | **updated** |
| Stock | written | **left alone** |
| Images | never written | never written |
| Products the shop added by hand | untouched | untouched |
| Orders, customers, carts, payments | untouched | untouched |

Stock is deliberately not reset. Otherwise someone tests an order, stock drops
to 7, the next deploy puts it back to 50, and the order that consumed it is
unaccounted for. Re-running refreshes the catalogue, not the warehouse.

## Removing it before launch

`DELETE /api/admin/catalog/test-data?confirm=true` deletes every product
flagged `is_test_data`, plus its variants, inventory and images.

It **refuses** to delete a test product that an order references, and reports
those ids instead. Deleting one would leave an order line pointing at nothing,
and an order history that cannot be rendered is a worse outcome than a leftover
test product — especially since those are the products someone tested checkout
with.

Categories are left in place. They are shared with real products and are not
test data.
