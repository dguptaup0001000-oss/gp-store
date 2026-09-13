# Onboarding a shop

**Shop #1 is not a special case.** It is the first shop of the marketplace, it
uses the same tables, the same routes and the same tenant scope as every shop
after it, and nothing in this document treats it differently. There is no
single-shop mode to leave and no migration to run — the second shop is opened
exactly as the first one was.

This is the sequence a real merchant goes through. Every step is an API call a
platform administrator or the merchant themselves actually makes. There is no
seeding script in the happy path and no SQL.

> **Steps 0–5 are a screen now — and it is the first screen of its own app.**
> **GP-STORE Super Admin** (`gpstore-superadmin-release.apk`,
> `in.gpstore.superadmin`) opens on **Merchants & Shops**: it opens the
> merchant's login, registers the merchant, walks it through review, opens a
> shop under it, and puts an account on that shop's staff list. The API calls
> below are what those buttons send, and remain the reference — but opening a
> real shop no longer needs a terminal.
>
> The same screen is still reachable in **GP-STORE Admin** under
> **Marketplace → Merchants & Shops**, as the last group in the sidebar and
> only for an account holding `PERM_PLATFORM_ADMIN`. Being last in a sidebar
> inside an app whose home screen is one shop's trading day is exactly why the
> separate APK exists.
>
> Steps 1–5 had existed and been tested since the marketplace slice; the
> console only ever *listed* merchants and shops and moved them between
> statuses. That made the one thing only the platform owner can do the one
> thing the platform owner could not do from the app.
>
> **Step 0 did not exist at all until now.** No API could make an account an
> `ADMIN` — the only role ever assigned anywhere in the code was
> `DELIVERY_BOY`, set with a rider's roster row — so a merchant's login had to
> be written with SQL on the box. `POST /api/platform/staff` closes that, and
> is the reason this document no longer has a manual step in its happy path.

---

## The sequence

| # | Who | Call | What it means |
|---|---|---|---|
| 0 | Platform | `POST /api/platform/staff` | The merchant's **login** is opened, as an `ADMIN`. Returns a one-time password, once. |
| 1 | Platform | `POST /api/platform/merchants` | The **business** applies, with step 0's account as `ownerCustomerId`. Not a shop yet. |
| 2 | Platform | `PUT /api/platform/merchants/{id}/status` → `PENDING_REVIEW` | Somebody is looking at the papers. |
| 3 | Platform | `PUT /api/platform/merchants/{id}/status` → `APPROVED` | The papers are in order. |
| 4 | Platform | `POST /api/platform/shops` | The **storefront** opens, as a `DRAFT`. Refused if the merchant is not approved. |
| 5 | Platform | `POST /api/platform/shops/{id}/staff` | Somebody can now sign in to it. `asDefault: true` makes it that account's home shop. Step 1's owner is added automatically; this is for anyone else. |
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

## Which app each person installs

| App | applicationId | Who | Opens on |
|---|---|---|---|
| GP-STORE | `in.gpstore.customer` | Shoppers | The shop |
| **GP-STORE Super Admin** | `in.gpstore.superadmin` | **The platform owner, only** | **Merchants & Shops** |
| GP-STORE Admin | `in.gpstore.admin` | A merchant and their staff | That shop's dashboard |
| GP-STORE Worker | `com.gpstore.worker` | Riders | Today's packing list |

Four applicationIds, so all four install side by side — which matters during
onboarding, when the owner and the merchant are often sitting at the same
counter with one phone each.

**None of this is a security boundary, and none of it is meant to be.** Every
route is gated server-side on the signed-in account's live role:
`/api/platform/**` needs `PERM_PLATFORM_ADMIN`, a permission `RolePermissions`
builds every shop role by *subtracting*. A merchant who sideloads the Super
Admin APK is refused by the backend on every screen in it. What the split buys
is a home screen that is the right one and an icon that says which hat you are
wearing.

The Super Admin app has **no push notifications and no Play bundle**, both on
purpose. Every notification this project sends is about one shop's order, and
this is not the app anybody runs a shop from; and a Play listing for the
platform owner's own console is not something to produce by accident. It is
sideloaded from the CI artifact.

---

## The merchant's login, and why the platform owner cannot keep it

`POST /api/platform/staff` creates the account and returns a **one-time
password**. That password:

- is shown **once**, in the response and the dialog that displays it, and is
  stored nowhere — what the database holds is a bcrypt hash, exactly like every
  other password. There is no route that returns it again;
- is generated server-side from an alphabet with `l`, `1`, `I`, `O` and `0`
  removed, 14 characters long, because it gets read out over a phone;
- buys **exactly one route**. The account arrives with
  `customers.must_change_password = true`, and while that is set `JwtFilter`
  refuses every request but `PUT /api/auth/change-password` with a 403 carrying
  `"code":"PASSWORD_CHANGE_REQUIRED"` — `/api/customers/me` included.

**The refusal is in the filter, not the app, and that is the point.** A client
that merely *showed* a change-password screen could be navigated around — a
deep link, an older build, a hand-built request — and the account would then be
working normally on a credential the platform owner also knows. Every action it
took would be deniable. Enforcing it server-side is what makes a merchant's
actions their own.

Because the gate refuses `/api/customers/me` too, a client cannot *ask*
whether it owes a change, so the login and refresh responses carry
`mustChangePassword`. The Flutter app deliberately acts on the **403 code**
instead: it is the one signal that cannot go stale, and it is the only one that
catches a password the platform **reset** while somebody was already signed in.
The screen it shows is the same one, shown in place of the app rather than over
it, with the system back gesture refused and a sign-out as the only other way
out.

Three paths clear the flag, and each proves the holder chose the password by a
different challenge the platform owner cannot answer: the signed-in change
(knows the current password), the OTP reset, and the reset-token reset. A
merchant who loses the handover slip before using it therefore has a way in
that is not "ask the platform owner to read it back" — there is no such route.

`POST /api/platform/staff/{customerId}/reset-password` issues a **new**
one-time password, revokes every refresh token the account holds, and puts the
gate back. It does not, and cannot, read the existing one.

Roles this route will open: `ADMIN`, `MANAGER`, `INVENTORY_MANAGER`,
`ORDER_MANAGER`, `DELIVERY_MANAGER`, `SUPPORT`. It refuses `SUPER_ADMIN` (a
second platform owner is a deliberate change at the database), `CUSTOMER` (they
register themselves) and `DELIVERY_BOY` (a rider's account is created with
their roster row).

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
