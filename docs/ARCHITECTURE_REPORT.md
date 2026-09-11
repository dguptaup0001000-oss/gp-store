# GP-STORE — Final Architecture Report

*Part 4 §26. Written to be read by somebody deciding whether to put real money
through this, which is why §24 is the longest section and the one to read
first if you only read one.*

Everything below describes the code as it stands on this branch. Where
something is claimed, a test name is given. Where something has **not** been
executed in this environment, it is marked **UNVERIFIED** rather than
described as done (§27).

---

## 1. What GP-STORE is now

A single-shop kirana application that has been turned into a multi-merchant
hyperlocal marketplace **without the original shop ceasing to work**. The shop
that existed before this transformation is Shop #1: the same rows, the same
orders, the same customers. Every piece of work described here had to leave it
running, and `SingleShopBrowseIsUnchangedTest` and `ShopOneIsTheExistingShopTest`
are the two that say so out loud.

The governing sentence for every design decision below is §103 of the brief:

> **LOCAL BUSINESSES REMAIN INDEPENDENT. GP-STORE PROVIDES THE TECHNOLOGY.**

Where a choice was available between "the platform decides" and "the merchant
decides, inside a stated limit", the second was taken — delivery, cancellation
charges, trading hours, pricing. Where the platform does decide, the limit is
configuration rather than a constant, and the merchant can read it.

## 2. Platform modes

Three, in `PlatformMode`, chosen by `platform.mode`:

| Mode | What it means |
|---|---|
| `SINGLE_SHOP` | The default. One shop, no marketplace surface, tenancy machinery inert. |
| `MULTI_SHOP_DEMO` | Multi-shop with demo merchants flagged as such. |
| `MULTI_SHOP_PRODUCTION` | Real merchants, real money. |

`SINGLE_SHOP` is the default **and the fallback for an unrecognised value**
(`PlatformProperties.parse`). A typo in an environment variable must not be
able to put a deployment into a mode it was not configured for, and
`SINGLE_SHOP` is the mode that changes nothing.

## 3. Tenancy: how a request gets a shop

`TenantContextFilter` resolves a scope once per request and puts it in
`TenantContext` (a thread-local `TenantScope`, either `ofShop(id)` or
`platform()`).

The rule that matters is §78: **the tenant never comes from the request.** A
client may send `X-Shop-Id`, and `TenantResolver.select` treats it as a
request to **narrow**, never as a grant. A customer whose credential resolves
to shops A and B may ask for A; a customer whose credential resolves to A
cannot ask for B and get it.

`TenantContextFilter.spansEveryShop()` lists the handful of paths that
legitimately have no single shop: `/api/auth/`, `/api/worker/auth/`,
`/api/payments/webhooks/`, `/api/marketplace/`, and `/api/addresses` (except
`/deliverable`). That list is short on purpose and is asserted by
`TheTenantNeverComesFromTheRequestTest`.

## 4. The tenant boundary, and its blind spots

Two mechanisms, because one is not enough:

1. **`ShopScopeFilter`** — a Hibernate `@Filter` over the 23 entities that
   implement `ShopOwned`, enabled for the whole transaction. It rewrites JPQL
   and entity queries into `... AND shop_id = ?`.
2. **`TenantEntityListener`** — `@PrePersist` stamps the shop onto every new
   shop-owned row; `@PostLoad` refuses one that belongs to a different shop.

The second exists because the first has **three blind spots**, all of them
named and all of them closed:

| Blind spot | Why the filter misses it | What closes it |
|---|---|---|
| `find()` by primary key | a PK load is not a query | `@PostLoad` → `CrossShopAccessException` |
| following an association | same | `@PostLoad` |
| native SQL | Hibernate does not rewrite it | `Storefront` binds the shop id explicitly |

`Storefront` is the single answer to "is this on this shop's shelf", used by
every native browse query. Before it existed, **every** native query in
`ProductBrowseRepository` was catalogue-wide: search, brand browse, the
bestseller collage, the "+N more" count, and the best-selling counter — which
told one merchant what was selling next door.

The attack suite (`CrossTenantApiAccessTest`, `CrossTenantDataIsolationTest`,
`CrossTenantShopCatalogTest`, `ShopScopeIsNotOptionalTest`) tests this at the
**HTTP layer**, not the service layer, because §23 of Part 4 is explicit: *do
not accept "the frontend hides it"*.

## 5. Catalogue: central product, per-shop shelf

§10: one `PRODUCT` row for the marketplace, one `SHOP_PRODUCT` /
`ShopProductVariant` row per shop that stocks it. A shop does not own the fact
that Aashirvaad atta exists; it owns the fact that **it** sells it, at **its**
price.

This is why `Product`, `ProductVariant` and `Category` deliberately do **not**
implement `ShopOwned`, and why a price edit is a change to a shop's listing
rather than to the catalogue.

Three product states (§7):

| State | What the customer sees |
|---|---|
| Not listed by this shop | nothing at all |
| Listed, in stock | price, ADD enabled |
| Listed, **out of stock** | still visible, "Sold out", ADD disabled |

State 2 is the one that is easy to get wrong, and getting it wrong is
customer-visible: a live ADD button on an empty shelf. `VariantResponse`
carries `inStock` separately from `available`, the Flutter card reads
`isBuyable = available && (inStock ?? true)`, and
`OutOfStockIsVisibleButUnbuyableTest` holds it.

## 6. Storefront and marketplace browse

Two intentional scopes:

- **Storefront** — every browse surface narrowed to one shop's shelf.
  `EveryBrowseSurfaceShowsOneShelfTest` covers fourteen of them; the point of
  enumerating them is that eight surfaces phrased slightly differently is how
  one of them ends up wrong.
- **Marketplace** (`/api/marketplace/**`) — cross-shop **on purpose**, and the
  only place it is allowed.

Discovery is local-first: `ShopDiscovery` searches 3 km, then 5, 10, 15, 25,
and `GET /api/marketplace/discovery` exposes "search farther" as an explicit
step rather than silently widening.

Cache keys are shop-aware (`CacheConfig.keyGenerator` prefixes
`"shop:<id>"` or `"platform"`), and `ShopShelfCache.changed()` evicts the nine
browse caches whenever a listing changes. That eviction was added because of a
real, found defect: **a shopkeeper repriced an item and the customer app went
on showing the old price for the full ten-minute TTL.**

## 7. Shop identity, verification and trust

A shop is a business: logo, business name, GSTIN, FSSAI licence, policies
(`ShopPolicy`, kinds DELIVERY / CANCELLATION / RETURNS).

Verification has three levels (`ShopVerificationLevel`): `NONE`,
`VERIFIED`, `BUSINESS_VERIFIED` — granted by the platform, never settable by
the merchant. `ShopSelfServiceController.ProfileUpdate` has no verification
field, and **the absence is the feature**.

"TRUSTED" is separate and **computed, never stored** (`ShopReliability`): 90-day
window, ≥25 orders, ≥92% completion, ≤8% returns. It is earned by trading
well and cannot be granted, bought, or set. `ShopVerificationIsEarnedTest`
is what stops that changing quietly.

## 8. Trading hours, pauses and closures

Per shop (V55), in three layers, resolved by `ShopHours`:

1. a dated override (`shop_hours_override`)
2. the weekly pattern (`shop_business_hours`, one row **per session** — a shop
   that shuts for lunch has two rows for that day)
3. the deployment default, for a shop that has said nothing

`isConfigured()` distinguishes "no rows at all" from "no rows for Sunday",
because the first means *inherit* and the second means *closed on Sundays*.

Pausing (`paused_until`) has **no scheduler**: the read path compares the
clock, so a pause that has run out is already over the next time anybody asks.
A job would be a second place that could disagree with the first, and it would
be wrong for exactly as long as it was late.

**An accepted order survives all of it.** Pausing, switching off, and closing a
day are about the *next* customer. `AnAcceptedOrderIsStillOwedTest` asserts
what did *not* change.

## 9. Orders: the lifecycle as a table

`OrderLifecycle` holds the transitions as data, not as `if` statements
scattered through a service. Statuses include `REJECTED` (reachable **only**
from `PENDING_CONFIRMATION`, per §2 — a shop that has said yes owes the
order), `DELIVERY_FAILED` (→ `OUT_FOR_DELIVERY` / `DELIVERED` / `CANCELLED`)
and `COMPLETED` (only from `DELIVERED`).

`orders` carries `ended_by`, `ended_reason`, `ended_at` and **`fault` as a
separate column**. One column cannot say both: a customer who cancels because
the shop rang to say the atta never arrived *cancelled it* and is *not at
fault for it*, and §12 turns on exactly that distinction.

Building the table found a real bug: making `CANCELLED` reachable through
`updateOrderStatus` gave a back door that set the column **without restoring
inventory or starting the refund**. `OrderStatusStateMachineTest` caught it the
moment it became reachable. `updateOrderStatus` now refuses both terminal
words and routes them through the shared `endOrder`.

## 10. Multi-shop carts and order groups

§16: a basket spanning two kiranas becomes one order **group** and N shop
orders, each owned, priced, paid and delivered by its own shop.
`OrderGroupService` wraps the per-shop work so that a failure in one rolls the
whole checkout back rather than leaving a customer with half a basket bought.

## 11. Cancellation, charges and dues (Part 3 §9–§12)

- **§9 — the five-second window survives**, and has moved somewhere it cannot
  be lost. It used to exist only as a timer a screen drew; it is now
  `store_operations_settings.free_cancellation_seconds`, defaulted to 5,
  enforced by the code that takes the money, widenable by a generous shop.
- **§10 — the merchant sets the charge, the platform caps it.**
  `cancellation_fee_percent` is the shop's, nullable, and null is the default
  because a fee nobody chose is a charge nobody agreed to. The ceiling is
  `platform.cancellation.max-fee-percent` (default 5). `CancellationPolicy`
  clamps at the point the money is worked out, not only where the form is
  validated.
- **The customer sees it first.** `GET /api/orders/{id}/cancellation-quote`
  runs the same method the cancellation runs, so the number agreed to and the
  number taken cannot drift. The quote says *how* it will be taken, because
  "deducted from your refund" and "added to your next order here" are
  different promises.
- **§11 — COD leaves a debt.** Nothing was collected, so the charge becomes a
  shop-scoped `customer_cancellation_dues` row: visible to the customer,
  invisible to the shop's competitors, one per cancelled order (unique index).
- **§12 — a merchant-fault cancellation is free**, checked *before* the shop's
  terms are read. An undecided fault is free too.

`ACancellationHasAPriceTest` — 17 tests.

## 12. Returns and refunds

Returns exist (`ReturnService`, partial refunds, per-refund rows). Refund
intent is written **inside the cancelling transaction** and sent by the outbox
worker afterwards, so a provider timeout cannot roll back a cancellation and
a crash cannot produce a cancelled order that owes a refund nothing will send.

Refund channel is decided and recorded (`CASH` vs `GATEWAY`) at the moment of
cancellation. Before that existed, a cancelled prepaid order sat at
`REFUND_PENDING` with no channel, which read to `completeRefund` as a cash
refund — so pressing "complete" stamped it `REFUNDED` while the customer's
money was still at the provider.

**Not built:** merchant-arranged return pickup (§13) and replacement-instead-of-refund
(§14). See §24.

## 13. Delivery

§3 of Part 3: **GP-STORE does not control merchant delivery.** Each shop has
its own territories, its own zones, its own riders (Decision W4 — no shared
pool), its own pricing. Delivery charges carry **0% GP-STORE commission**
(Part 4 §8), and that exclusion lives in one place, `MerchantSales`, rather
than being remembered at each call site.

**Not built:** merchant ETA as an explicit non-guarantee (§4). See §24.

## 14. Workers

The worker app has its own credentials on `delivery_partners` (not a customer
row), its own auth path, and a shop identity. Pack scanning, typed pack codes,
delivery status transitions, GPS and foreground location all survive the
transformation — `WorkerPackScanTest`, `TypedPackCodeTest`,
`WorkerDeliveryStatusTest`, and Part 4 §17's requirement that existing worker
functionality not disappear.

## 15. Payments — and the boundary that is not crossed

This is the most important section for anybody about to deploy.

**What exists today:** one deployment-wide payment account (`CashfreeProperties`).
Every shop's money passes through it.

**What the brief requires:** Decision W1 and Part 3 §7 — each merchant collects
directly; GP-STORE is a technology marketplace, **not a payment aggregator**.

**What was built:** `PaymentCollection` / `PaymentCollectionModel` — a clean
abstraction and configuration boundary (Part 1 §17), with a `forShop(shopId)`
that returns the collector for a shop, and a constructor that **throws** if
`MERCHANT_COLLECTS` is configured. That last part is deliberate: Part 3 §7 and
Part 4 §10 both say *do not invent a payment provider architecture*, so the
code refuses to pretend it has one.

**The gap is real and it is commercial, not technical.** Running one account
for many merchants makes GP-STORE a payment aggregator, with the regulatory
consequences that follow in India. See §24.

## 16. Billing

Built to the *shape* the brief gives, with **no commercial amount anywhere in
it** (Part 4 §5).

- `billing_plan` — tier (`SMALL`/`MEDIUM`/`LARGE`), weekly fee, commission in
  basis points, effective-from/to. No seeded plan; V58 fails if one appears.
- `billing_period` — one per merchant per ISO week, `OPEN`/`CLOSED`/`SETTLED`.
- `merchant_ledger_entry` — **append-only, enforced by a Postgres trigger**
  that raises on UPDATE and DELETE. A correction is a reversal row that points
  at what it reverses. There is no balance column: a balance is a sum of rows,
  and a stored one is a second place the truth lives.
- Hybrid revenue `max(P, C)` (§6) implemented as +P, +C per sale, −min(P,C)
  credit — so the statement's rows sum exactly to the total.
- Commission is charged **per sale**, not as a weekly aggregate. That was a
  design correction found by a failing test: a single aggregate row with no
  order id made `reverseCommission` match nothing, so a refund reversed
  nothing (§8 requires reversal on refund).
- §8's exclusions — delivery charges, refunds, unsuccessful orders — live in
  `MerchantSales` and nowhere else.

`BillingPlanArithmeticTest.nothingIsHardCoded()` scans the `billing` package's
own source for money literals and fails if one appears.

## 17. Governance: a ladder, not a switch (Part 4 §2)

Before: `merchants.status` could be set to `SUSPENDED`. That was all of it —
no readable reason, nothing between "fine" and "your business is shut", no
record of who or why, no way to answer back.

Now (`V61`, `MerchantGovernance`):

- Four rungs — `WARNING`, `FINAL_WARNING`, `SUSPENSION`, `TERMINATION` — plus
  `REINSTATEMENT`, which points *down* and is recorded as its own step so that
  "suspended, then reinstated" is one story rather than a gap.
- **The rungs cannot be skipped.** A suspension with no final warning behind
  it is refused, and the error says what is missing.
- **The exception is narrow and is about harm, not revenue.** Three reason
  codes skip the ladder: counterfeit/unsafe goods, regulatory non-compliance,
  a legal order. `UNPAID_PLATFORM_DUES` and `UNPAID_REFUNDS` deliberately do
  **not** — a platform that can suspend a shop for owing it money without a
  warning has a governance ladder that is really a collection mechanism.
- **Warnings decay** (90 days; final warnings 180). A ladder with no way down
  is one every long-lived merchant eventually falls off.
- **One appeal each**, answered `UPHELD` / `REDUCED` / `OVERTURNED`. Winning
  reopens the shop; `REDUCED` drops a rung and reopens it too.
- The merchant reads their **own** record — reason, evidence, appeal outcome —
  at `/api/shop/governance`, with the merchant derived from the shop in scope
  and **never named in the request**.

`ALadderNotASwitchTest` — 15 tests.

## 18. Ratings and reviews (Part 3 §17–§22)

**§17 — two different questions.** A shop that delivered a perfect packet of a
mediocre biscuit must not carry the biscuit's stars. Product reviews stay
central (shared by every shop selling the item); `shop_ratings` are shop-owned.

**§18 — reasons as rows.** Three stars tells a shopkeeper nothing; three stars
and `DELIVERED_LATE` tells them to look at their dispatch times. Closed enums
(`ShopRatingReason`, `ProductReviewReason`) with a `praise` flag, capped at
five per rating.

**§19 — three figures, not one.** Lifetime average, recent (90-day) average,
and the verified count, because one number flatters a new shop and buries an
improved one. An unrated shop shows **zero, not a middling default**.

**§20 — genuine negatives remain, and that is schema, not policy.**
`moderateDeleteReview` used to be a hard `delete()` behind a permission: one
call, no reason, no record. It is now a **hide** that requires a reason from
`HideReason`, a closed list whose every value describes something wrong with
the *text* — abuse, personal information, spam, impersonation, off-topic,
illegal. There is no `UNFAIR`, no `INACCURATE`, no `OTHER`. The row survives,
so "why do this shop's one-star ratings keep disappearing" is a question the
database can answer. And a hidden rating **still counts towards the average**
(except spam and impersonation, which were never opinions) — because if hiding
improved the arithmetic, hiding would be worth doing for the arithmetic alone.

**§21 — one response each.** The merchant answers once, the customer replies
once. A merchant posting repeatedly could bury a one-star under their own
replies.

**§22 — reporting is not hiding.** A shop can flag a rating for a platform
reviewer; it stays visible and stays in the average. A merchant who could
suppress a rating by objecting to it would have a delete button with an extra
step. Plus: a rating costs a real order, an order buys exactly one, only
terminal orders can be rated, and an order the **customer** cancelled cannot
be rated at all (the fault column again).

`RatingTheShopIsNotRatingTheAttaTest` — 19 tests.

## 18b. Discovery modes, preferences and final cost (Part 2)

Part 2 arrived after Parts 3 and 4 and is built on the foundation rather than
beside it.

**§4 — preferences are per category, and there are two slots.** "My kirana" is
not "my hardware shop": a single global preferred shop is the design that
looks obvious and fails the first week somebody buys atta and screws. Up to
two shops per category, and "up to two" is enforced by *shape* rather than by
a count — each row occupies slot 1 or slot 2 under a unique index, so a third
preference has nowhere to go. A service-layer `count() >= 2` check would be
two concurrent requests away from being three.

**And a preference orders a list; it never shortens one.** §4 requires that a
customer in preferred mode can still see, compare, switch and buy elsewhere,
so `preferredFirst` reorders and returns every shop it was given. There is no
filter anywhere that consults a preference, and that absence is the feature.

**§5 — Best Deal cannot be bought.** The ranking is a lexicographic sort over
questions a customer would recognise, in the order they would ask them:
can you actually buy it, is the delivery charge known, what is the final cost,
which is nearer, which is more reliable. No weighted score, because weights
are invented numbers and an invented number that decides whose shop appears
first is exactly the thing that gets adjusted later for reasons nobody writes
down. And the promise that a merchant cannot pay for a position is kept by an
*absence*: `BestDeal` ranks `ShopOffer`s, and a `ShopOffer` has no commission,
tier, ledger or billing component — there is no route by which what a merchant
pays could reach the ranking. A reflection test fails if one is ever added.

**§7 — the 25% rule, on the final cost.** A farther seller qualifies when the
local final cost is at least 1.25× theirs. On the *product price* the brief's
own example is a dead heat at exactly 1.25; on the final cost it is 1.33 and
the farther shop wins comfortably. The dangerous case is the other direction —
a cheap product behind an expensive delivery looks like a bargain on the
product line and is not one — so the method takes finals and its parameters
are named for them. The multiplier is configuration, and a value below 1 is
rejected because that is not a relaxed rule, it is the opposite rule.

**§6 — the ladder is configuration now.** It was `3, 5, 10, 15, 25` as a
constant. A district where the next hardware shop is forty kilometres away and
a city where four kiranas share a street cannot use the same ladder, and
neither is wrong, so it is `marketplace.search.radii-km` with the old list as
the default — making it configurable changes no running deployment. A search
that finds nothing at the rung asked for climbs until it does and says so in
the server's own words ("No shops within 8 km. Showing shops within 20 km."),
because a client rebuilding that sentence from two numbers eventually rebuilds
it as "no shops nearby" when there are twelve, two rungs out. A malformed
ladder falls back whole rather than failing boot: a typo in one environment
variable taking the marketplace offline is a worse failure than running on the
default rungs.

**§10 — the price is now actually hidden.** "Keep visible, show Out of stock,
disable Add to Cart, hide price" — the first three were already true and the
fourth was not. `VariantResponse` withholds the price and MRP when the stock
is known to be zero, in the response rather than in a widget, because there
are three clients and a public API and a rule enforced in one Dart file is a
rule the other two do not have. Null stock — an admin catalogue screen, a
platform report — keeps its price, or the merchant's own product list goes
blank.

**§12 — there is no price floor, and now there is a test saying so.** A scan
of the whole main source tree for `PRICE_FLOOR`, `MIN_SELLING_PRICE` and their
neighbours, plus a case asserting that a shop selling at ₹12 against a
catalogue price of ₹500 is charged ₹12.

**§13 — the basket is drawn as the several purchases it is.** Per-shop
subtotal, delivery and total, with the combined figure named
`informationalCombinedTotal` and `isSinglePayment` stated beside it. A field
called `total` on a cart response is an invitation to draw it large and put a
Pay button under it, so there isn't one. A shop that cannot quote delivery
reports *unknown*, never zero — zero reads as free delivery and understates
the basket.

## 19. Caching

Redis, with shop-aware keys. `ShopHoursService` caches per shop and date range
(`@Cacheable("shopHours", sync=true)`) with `@CacheEvict` on every hours edit —
added because per-shop hours took the checkout query budget from 1 query to 4
and `CheckoutPerformanceTest` failed. The eviction is load-bearing and was
proved so by removing it and watching a named test fail.

`ShopHours` is `Serializable` with a pinned `serialVersionUID`, because a
cached value whose class silently changes shape is a deserialisation failure in
production and a green test suite in CI.

## 20. Concurrency

- `spring.jpa.open-in-view=false`.
- 11 `@Lock(PESSIMISTIC_WRITE)` sites, with **one lock order everywhere**:
  ORDER → PAYMENT → INVENTORY, and ORDER → DELIVERY. Taking them the other way
  round deadlocks against cancellation or checkout under load.
- No `@Version` optimistic locking; the pessimistic locks are the mechanism.
- Inventory restore is guarded to happen exactly once across every path that
  can trigger it (`restoreInventoryOnce`).
- Idempotency keys on checkout, with a canonical fingerprint so a retried
  request is told apart from a reused key.
- The outbox is the durability mechanism for anything that must survive a
  crash: invoice cancellation, refund dispatch, notifications.

## 21. Database

62 Flyway migrations, `ddl-auto=validate` in production. Documented bootstrap:
boot once with `DDL_AUTO=update`, then run under `validate`.

Every migration added during this transformation ends in a **VERIFY block that
raises** if the data is not what the migration intended — §92: *never assume a
successful migration means correct data*. Examples:

- V55 fails if any shop was given seeded hours.
- V56 fails if a `trusted` column appears (trust is computed, not stored).
- V57 fails if any row was backfilled with an `ended_by`.
- V58 fails on any seeded plan, tier or ledger row, or a missing append-only trigger.
- V59 fails if any shop was given a cancellation fee, if the five-second window
  did not reach every shop, or if anybody was given a debt.
- V60 fails if it invented a rating, hid an existing review, or wrote a
  merchant response nobody typed.
- V61 fails if it issued anybody a warning.

Constraints are discovered **by definition, not by name**, where Hibernate may
have generated a hash-named duplicate (V54, V57).

## 22. Security posture

- Authorization is on the **route**, in `SecurityConfig` — the layer a
  hand-built request cannot skip. Not in a controller, and never in Flutter.
- Every new surface added in this work got an explicit rule. Two were
  genuinely dangerous without one and are worth naming: `/api/reviews/**` is
  `permitAll` for GET, so a flagged-reviews moderation queue added under that
  prefix would have been **world-readable**; and `/api/cancellation-dues/*/waive`
  without a rule would have let any signed-in shopper waive their own debt.
- `AuditLogService` records every security-sensitive action: status changes,
  cancellations, refunds, hides, governance steps, hours edits, pauses.
- Tokens carry the shop; the request body never does.

## 23. What is tested, and how

**1727 backend tests** at the last full green run, 0 failures, 1 skipped.
255 test classes against a real Postgres — not an in-memory substitute, because
the filter behaviour, the check constraints and the append-only trigger are all
things H2 would quietly not have.

The method used throughout each slice:

1. implement
2. full `mvn clean verify`
3. **mutation checks** — remove the protection, confirm a *named* test fails
4. fresh-database bootstrap under `update`, then a second boot under `validate`
5. commit and push

Mutation results recorded during this work: Slice 17 killed 13/13; the Part 1
wave killed 7/7, including PA4 (an hours edit that never evicts the cache).

## 24. What is NOT done, NOT verified, and what needs your decision

**§27 applies here: GP-STORE is not production-ready because the application
builds. This section is why.**

### A. Needs your decision — I cannot do these

| # | What | Why it is yours |
|---|---|---|
| A2 | **Payment provider / direct-to-merchant settlement.** | Part 3 §7 and Part 4 §10 forbid inventing a provider architecture. Today one Cashfree account carries every shop's money, which makes GP-STORE a payment aggregator. That is a business and compliance decision, not a coding one. The abstraction boundary is built and waiting (`PaymentCollection`); the implementation behind it is not, on purpose. |
| A3 | **Commercial amounts.** | Tiers, weekly fees, commission rates. Part 4 §5 says these are not decided. The billing machinery takes them as data and contains no number. |
| A4 | **Cancellation fee ceiling.** | `platform.cancellation.max-fee-percent` defaults to 5 because §10 named 1–5%. If you want a different ceiling, it is one property. |
| A5 | **Governance thresholds.** | 90-day warning decay, 180-day final warning, and the reliability thresholds for TRUSTED (25 orders / 92% / 8%) are defensible defaults, not decisions you made. |

### B. Environment blockers — code is fine, this machine is not

| # | What | Evidence |
|---|---|---|
| B1 | **No Android APK or AAB can be produced here.** `dl.google.com` returns **403 through the proxy** (`CONNECT tunnel failed, response 403`). | This is a **BUILD ENVIRONMENT FAILURE, not a code failure** (Part 4 §25). The Gradle build cannot fetch the Android toolchain. |
| B2 | `OpsStatusServiceTest.diskOnARealDirectoryIsHealthy` fails whenever the 2.4 GB Flutter SDK is resident — it pushes free disk under the 10% floor the test asserts. | The SDK is deleted before backend runs and re-fetched for Flutter runs; the two cannot pass in the same invocation on this box. |
| B3 | `LazySerialisationTest` / `ReturnsTest` flake on a mobile-number collision from a `"9" + nanoTime % 1e9` fixture. | Pre-existing, not introduced here, and worth fixing. |

### C. UNVERIFIED in this session

These are **not** claimed as passing. They were passing when last run, in an
earlier session, against an earlier state of the code:

- **The Flutter test suite** — not executed in this session.
- **The real two-shop Flutter journey** against a `MULTI_SHOP_PRODUCTION`
  backend (registration → address → discover both shops → A shelf only → B
  shelf only → one basket → two shop orders → history → merchant → worker) —
  not re-executed since the cancellation, ratings and governance work landed.
  **The backend contracts it drives have changed.** Re-run it before release.
- **Load testing (Part 4 §14)** — not performed. The per-request query budgets
  are asserted (`CheckoutPerformanceTest`), which is not the same thing.
- **A fresh-database bootstrap** (`DDL_AUTO=update`, then `validate`) covering
  V59–V61 — the migrations ran forward against the existing test database and
  their VERIFY blocks passed, but the empty-database path has not been
  exercised for these three.

### D. Built to the shape, not to completion

| # | What | Where it stands |
|---|---|---|
| D1 | **Merchant ETA (Part 3 §4)** | Not built. Delivery windows exist; an explicit merchant-set, explicitly-not-a-guarantee ETA does not. |
| D2 | **Return pickup and replacement (§13, §14)** | Returns and refunds work. Merchant-arranged pickup and replacement-instead-of-refund are not built. |
| D3 | **Refund enforcement (§15)** | The ledger can carry `INTERVENTION_RECOVERY`; nothing issues one yet. |
| D4 | **Preferred Shops / Best Deal (Part 4 §4)** | Not built. Discovery is distance-first and there is **no pay-for-ranking anywhere** — which is the half of §4 that matters most, and is true by absence. |
| D5 | **Merchant notification of a governance action (§2)** | The action is recorded and the merchant can read it at `/api/shop/governance`. There is no push or email to the merchant, because there is no merchant notification channel in this system. A shopkeeper finds out by looking. |
| D6 | **§7's fee-refund eligibility rules** | Two of them could not be answered from the data and were deliberately **not stubbed** — a stub that always returns true is worse than a gap, because it looks finished. |

### E. The honest summary

The tenancy boundary, the catalogue split, the order lifecycle, cancellation,
billing, governance and ratings are built, tested against a real database, and
green. The payment model is the one structural thing that is deliberately
incomplete, and it is incomplete because completing it would mean inventing an
architecture the brief twice told me not to invent.

**Do not treat a green test suite as permission to take real money through
this.** B1 means there is no shippable Android artifact from this environment.
C means the customer-facing app has not been driven end to end against the
current backend. A2 means the money currently flows the wrong way.
