# Onboarding a shop

**Shop #1 is not a special case.** It is the first shop of the marketplace, it
uses the same tables, the same routes and the same tenant scope as every shop
after it, and nothing in this document treats it differently. There is no
single-shop mode to leave and no migration to run — the second shop is opened
exactly as the first one was.

This is the sequence a real merchant goes through. Every step is an API call a
platform administrator or the merchant themselves actually makes. There is no
seeding script in the happy path and no SQL.

> **Steps 1–5 are a screen now.** In the admin app, **Marketplace →
> Merchants & Shops** (the last group in the sidebar, visible only to
> `SUPER_ADMIN`) registers a merchant, walks it through review, opens a shop
> under it, and puts an account on that shop's staff list. The API calls below
> are what those buttons send, and remain the reference — but opening a real
> shop no longer needs a terminal.
>
> The routes had existed and been tested since the marketplace slice; the
> console only ever *listed* merchants and shops and moved them between
> statuses. That made the one thing only the platform owner can do the one
> thing the platform owner could not do from the app.

---

## The sequence

| # | Who | Call | What it means |
|---|---|---|---|
| 1 | Platform | `POST /api/platform/merchants` | The **business** applies. Not a shop yet. |
| 2 | Platform | `PUT /api/platform/merchants/{id}/status` → `PENDING_REVIEW` | Somebody is looking at the papers. |
| 3 | Platform | `PUT /api/platform/merchants/{id}/status` → `APPROVED` | The papers are in order. |
| 4 | Platform | `POST /api/platform/shops` | The **storefront** opens, as a `DRAFT`. Refused if the merchant is not approved. |
| 5 | Platform | `POST /api/platform/shops/{id}/staff` | Somebody can now sign in to it. `asDefault: true` makes it that account's home shop. |
| 6 | Merchant | `GET /api/shop/readiness` | What is still missing, in the shopkeeper's words. |
| 7 | Merchant | `POST /api/admin/territory/zones` + `/subzones` | Where they deliver. |
| 8 | Merchant | `POST /api/delivery-partners` | Who delivers it. Their riders, nobody else's. |
| 9 | Merchant | `PUT /api/shop/listings/{variantId}` | What they sell and **what they charge**. |
| 10 | Merchant | `PUT /api/shop/listings/{variantId}/stock` | **How much of it they have.** |
| 11 | Platform | `PUT /api/platform/merchants/{id}/status` → `ACTIVE` | The business is trading. |
| 12 | Platform | `PUT /api/platform/shops/{id}/status` → `ACTIVE` | The shop is trading. |

**Steps 11 and 12 are two switches and both are real.** A shop can be `ACTIVE`
under a merchant that is only `APPROVED`: everything built, nothing selling.
That is the state a real onboarding sits in until somebody throws the second,
and `GET /api/shop/readiness` is the only screen that says so.

**Until step 12 the storefront returns 404,** not 403. Whether a particular
shop exists but is suspended is between the platform and that merchant.

---

## What the new shop gets, and where it lives

Each of these is a per-shop row or set of rows, carrying `shop_id`, read
through the `shopScope` Hibernate filter. None of them is shared with another
shop.

| | Where | Created |
|---|---|---|
| Shop id | `shops` | Step 4 |
| Merchant ownership | `shops.merchant_id` → `merchants` | Step 4 |
| Products and variants | `shop_product_variants` → the shared `product_variants` | Step 9 |
| Prices | `shop_product_variants.selling_price` / `cost_price` / `mrp` | Step 9 |
| Stock | `inventory`, unique on **(shop_id, product_variant_id)** | Step 10 |
| Shop hours | `shop_business_hours`, `shop_hours_override` | Merchant sets; see below |
| Delivery settings | `delivery_pricing_settings`, `delivery_zones`, `delivery_subzones` | Pricing at step 4, territory at step 7 |
| Workers | `delivery_partners`, `shop_staff` | Steps 5 and 8 |
| Orders | `orders`, `deliveries`, `payments`, `invoices` | When customers buy |
| Ratings | `shop_ratings`, `customer_delivery_ratings` | When customers rate |
| Offers | `coupons` | Merchant creates |
| Settings | `store_operations_settings`, `shop_policies` | Operations at step 4 |

**Categories are deliberately NOT per-shop.** The catalogue is shared: one
`products` / `product_variants` / `categories` tree that every shop draws from,
so two shops selling Tata Salt 1kg are selling *the same* Tata Salt 1kg and a
customer can compare them. What is per-shop is the **offering** — whether this
shop lists it, at what price, and how much of it there is. A shop that owned
its own categories could not be compared with any other, which is most of what
a marketplace is for.

### Hours are the one thing a new shop does not get

A shop with no `shop_business_hours` rows is **not shut** — `ShopHours.isConfigured()`
is false and every day falls back to the deployment-wide default hours. That is
harmless for Shop #1, whose hours the deployment defaults *are*. It is wrong for
every shop after it, which is a marketplace's problem and not a single shop's.

Hours are **not** created automatically, because opening a shop with seven
invented days would read as a decision the shopkeeper made, and the first
customer at a shut shutter would be the one who found out otherwise. Instead
`GET /api/shop/readiness` carries a non-blocking `shop-hours` step that says,
in the shopkeeper's words, that they are trading on GP-STORE's clock until they
set their own.

---

## Isolation

Two things are true at once and both are load-bearing:

- **The catalogue is shared.** `GET /api/products/admin/all` shows every shop
  the same list to pick an opening range from.
- **The shelf is not.** `GET /api/products` shows only what *this* shop sells.
  A shop that has listed nothing sells nothing.

Everything shop-owned is scoped by the `shopScope` filter over 25 `ShopOwned`
entities, resolved by `TenantResolver` from the credential — never from a
request parameter, which may narrow a scope and can never grant one.

**Refusals are 404, never 403.** A 403 on an id you guessed confirms the id
exists; walking a range of ids and reading which come back 403 maps out how much
business a competitor is doing. "Not found" is the only answer that leaks
nothing.

---

## Verifying it

Two layers, and they make different claims.

**In the suite** — behaviour, under MockMvc, in a rolled-back transaction:

- `SecondMerchantOnboardingTest` — the whole journey, registration to first
  delivery.
- `AShopStocksItsOwnShelfTest` — listing and stocking, and two shops holding
  their own count of the same catalogue item.
- `CrossTenantApiAccessTest` / `CrossTenantDataIsolationTest` — the boundary,
  attacked by changing an id.
- `AMerchantWithSeveralShopsTest` — one owner, several shops.
- `ShopOneIsTheExistingShopTest` — Shop #1 is unchanged.

**Against a running server** — `scripts/verify/onboard_second_shop.sh`, which
opens a real second shop over real HTTP through the routes above and then tries
to break the wall in both directions. It uses no SQL and no test-only endpoint:
if a step cannot be done through the API, the script cannot do it either, which
is the point. It takes credentials rather than bearer tokens, because access
tokens last fifteen minutes and a 401 halfway through a run looks exactly like
a tenancy refusal if you are not reading carefully.

```
PLATFORM_EMAIL=... PLATFORM_PASSWORD=... \
OWNER_B_EMAIL=... OWNER_B_PASSWORD=... \
OWNER_A_EMAIL=... OWNER_A_PASSWORD=... \
  scripts/verify/onboard_second_shop.sh http://localhost:8081/v1
```

It creates one merchant, one shop and one listing, all flagged demo, and writes
**nothing** to Shop #1 — every request it makes against Shop #1 is one it
expects to be refused. Close the shop when you are finished with it: a demo
shop nobody closes is a shop a customer can eventually be offered.

---

## Two things this flow got wrong, and what fixed them

Both were found by running the onboarding for real rather than by reading it.

**There was no route to stock a shelf.** A shop could set its price and had no
way at all to say how much it had: `/api/inventory` wants a whole `Inventory`
entity and the stock row's own id, which a listing created a second ago has not
got. `SecondMerchantOnboardingTest` papered over it with a `jdbc.update("INSERT
INTO inventory ...")`, which made the journey pass and would have left a real
merchant stuck. `PUT /api/shop/listings/{variantId}/stock` is the missing half,
and the test now calls it instead of SQL.

**`asDefault` was a silent no-op for anybody who already had a shop.**
`grant(..., asDefault=true)` checked whether the account already had a default
and, if it did, dropped the flag: HTTP 200, nothing changed. That is exactly the
case onboarding hits — a merchant opening their *second* shop already has a
first. The administrator was told the owner's home was the new shop; the owner
signed in and landed in the old one.

The first fix for that was wrong in the other direction, and the suite said so
immediately: making `grant` move the default *unconditionally* broke five tests
in `MarketplaceIdentityTest`, because `ShopLifecycleService.open()` also calls
it — to give a brand new storefront somebody who can sign in to it. Under that
fix, a merchant opening a second branch was silently relocated out of the shop
they were working in. The two callers want different things, so there are now
two methods:

- `grant(shopId, customerId, asDefault)` — "if they have no home shop yet".
  Bootstrapping access. What `open()` needs.
- `grantAndMakeDefault(shopId, customerId)` — moves the home shop, clearing the
  old row first because `uk_shop_staff_one_default` will not hold two. What the
  platform console's explicit request means.

Both directions are pinned by a test, because the mistake was available in
both.
