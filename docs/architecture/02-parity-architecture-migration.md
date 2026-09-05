# GP-STORE — Phase 1 completion, Phase 2 architecture, Phase 3 migration plan

Companion to `01-multi-merchant-audit.md`. That document inventoried the codebase.
This one closes Phase 1 with the feature parity matrix §75 asks for, then sets out the
target architecture (Phase 2) and the migration sequence (Phase 3).

Commit: `2e9f624`. **No behaviour changed by this document.**

---

# PHASE 1 (completed) — traced flows

§74 requires flows traced through the stack rather than inferred. Two representative
traces, one customer and one staff:

### Trace 1 — a customer adds an item to the cart

```
CartAwareProductCard (lib/shared/widgets/cart_aware_product_card.dart)
  └─ cartControllerProvider.addToCart(variantId, quantity)      [Riverpod AsyncNotifier]
      └─ CartRepository → POST /api/carts/add                    [Dio, JWT attached by interceptor]
          └─ CartController.addToCart                            [/api/carts, authenticated]
              └─ CurrentUser.customerId()                        ← identity from JWT, never the body
                  └─ CartService.addToCart(customerId, variantId, qty)
                      └─ carts (1:1 customer) → cart_items → product_variants
                          └─ CartResponse ─────────────────────► cartLineForVariantProvider
                                                                  → stepper re-renders
```

**Finding:** the customer path takes identity from the token, never from the request
body. `/api/cart-items/**` is a *separate*, staff-only controller — writes need
`CUSTOMERS_MANAGE`, reads `CUSTOMERS_VIEW`. That split was made after a real
escalation (a `SUPPORT` token wrote cart row 8421 and cleared a basket); the comment
in `SecurityConfig` records it. **This is the pattern to generalise for shop scoping:
scope comes from the credential, not from the payload.**

### Trace 2 — staff changes an order's status

```
AdminOrderDetailScreen → adminProductsRepositoryProvider.updateOrderStatus(orderId, status)
  └─ PUT /api/orders/{id}/status                       [ORDERS_MANAGE — global permission]
      └─ OrderController → OrderService.updateOrderStatus
          └─ lock ORDER → PAYMENT → INVENTORY (fixed order, deadlock-safe)
              └─ validated state-machine transition
                  └─ COD auto-settle on DELIVERED
                      └─ outbox event → notification, after commit
                          └─ OrderDetailResponse (payment status read from payments row)
```

**Finding:** no ownership check anywhere in this path — `ORDERS_MANAGE` grants it over
*every* order, because every order belongs to the one shop. This is the exact line
where shop isolation has to be inserted, and it is representative of ~30 similar
staff paths.

---

# PHASE 1 (completed) — feature parity matrix

Scope: **P** platform · **M** merchant · **S** shop · **W** worker · **C** customer

Status vocabulary per §101: `Pending` = designed, not built. `No change` = stays as
is. Nothing below is marked implemented, because nothing is.

## Customer features

| # | Current feature | Current location | Future scope | Migration required | Status |
|---:|---|---|---|---|---|
| 1 | Password registration / login | `AuthController`, `customers` | P | Identity stays platform-level | No change |
| 2 | OTP login (email / SMS) | `otp/*`, `otp_verifications` | P | None | No change |
| 3 | Password reset | `password_reset_tokens` | P | None | No change |
| 4 | Refresh-token rotation + families | `refresh_tokens` | P | Add shop scope to *staff* tokens only | Pending |
| 5 | Profile + photo | `ProfilePhotoController`, R2 | P | None | No change |
| 6 | Address book, map pin, directions | `addresses`, `AddressController` | C | Serviceability resolved per shop | Pending |
| 7 | Home / product feed | `ProductController`, `productFeed` cache | C→P | Feed becomes marketplace-wide, location-filtered | Pending |
| 8 | Category browse | `browseByCategory` | C→P | Catalogue category, offers from many shops | Pending |
| 9 | Brand browse | `browseByBrand` | C→P | Same | Pending |
| 10 | Search (typo-tolerant, synonyms, trigram) | `search/*` | P | Add shop + distance dimension (§81) | **Required** |
| 11 | Product detail + variants | `getProductById` | P + S | Catalogue detail + per-shop offers list | **Required** |
| 12 | 3D model view | `model_3d_url` | P | None | No change |
| 13 | Product image gallery | `product_images` | P | None | No change |
| 14 | Wishlist | `wishlist` | C | On catalogue product, not shop offer | Pending |
| 15 | Cart | `carts` 1:1 customer | C | Lines gain `shop_id`; grouped by shop | **Required** |
| 16 | Coupons: validate / list active | `coupons` | P + S | Platform vs shop coupons, separately funded | **Required** |
| 17 | Checkout preview (price, ETA, deliverability) | `previewCheckout` | C | Per shop-group preview | **Required** |
| 18 | Place order (idempotent) | `placeOrder` | C→S | One order group → N shop orders (§16) | **Required** |
| 19 | COD | `PaymentService` | S | Per shop-order | **Required** |
| 20 | UPI link | `UpiPaymentService`, `STORE_UPI_ID` | S | Built from the **shop's own VPA** (decision W1) | **Required** |
| 21 | Online payment (Cashfree) | `payment/gateway` | M | Merchant's own account. UPI+COD first, no credential custody (W1 §3) | **Required** |
| 22 | Order history | `getMyOrders` | C | Spans shops; grouped | **Required** |
| 23 | Order detail / tracking | `getOwnedOrderDetail` | C + S | Per shop-order, with shop identity shown | **Required** |
| 24 | Order cancellation | `cancelOrder` | C + S | Per shop-order | **Required** |
| 25 | Returns | `returns/*` | S | Per shop-order; platform arbitration for disputes | **Required** |
| 26 | Reviews | `reviews` | P + S | Product review vs shop/service review | **Required** |
| 27 | Notifications | `notifications` | C + S | Scoped; customer sees per-shop-order events | Pending |
| 28 | Store status / hours | `store/*`, singleton | S | Per shop | **Required** |
| 29 | Support / contact | `StoreInfoController`, `STORE_SUPPORT_*` | P + S | Platform support + shop contact | Pending |
| 30 | App-session tracking | `customer_app_sessions` | P | None | No change |
| 31 | Client crash reporting | `client_crash_reports` | P | None | No change |

## Merchant / shop features (the current admin console)

Every one of the 23 admin screens is listed. §85 requires the merchant dashboard to
preserve this depth — none of these may be dropped.

| # | Current feature | Current location | Future scope | Migration required | Status |
|---:|---|---|---|---|---|
| 32 | Staff login (9 roles) | `Role` on `customers` | M + S | Split into merchant users with per-shop role bindings (§77) | **Required** |
| 33 | Dashboard: revenue, live clock, concurrent shoppers | `admin_dashboard_screen`, `AnalyticsController` | S | Own shop's figures only | **Required** |
| 34 | Order list | `admin_order_list_screen` | S | `shop_id` filter, default-deny | **Required** |
| 35 | Order detail + status transitions | `admin_order_detail_screen` | S | Ownership check before every transition | **Required** |
| 36 | Packing list (morning preparation) | `morning_preparation_screen` | S | Per shop | **Required** |
| 37 | Store hours / closures | `store_operations_screen`, singleton `id=1` | S | One settings row per shop | **Required** |
| 38 | Payments: confirm UPI | `admin_payments_screen` | S | Per shop-order | **Required** |
| 39 | Refunds, full and partial | `refunds`, `PaymentService` | S | Platform **tracks the obligation**; the merchant executes it (W1 §2) | **Required** |
| 40 | Returns queue | `admin_returns_screen` | S | Per shop | **Required** |
| 41 | Delivery breaches | `admin_delivery_breaches_screen` | S | Per shop | **Required** |
| 42 | Product list | `admin_product_list_screen` | S | Lists the shop's *offers*, not the catalogue | **Required** |
| 43 | Product create / edit | `admin_product_form_screen` | P + S | Catalogue request (P) + offer edit (S) | **Required** |
| 44 | Variant management | `ProductVariantController` | P + S | Catalogue variant (P), shop price/stock (S) | **Required** |
| 45 | Categories | `admin_category_list_screen` | P | Central taxonomy; shops select | **Required** |
| 46 | Inventory, restock, low-stock alerts | `admin_inventory_screen`, `inventory` | S | Hangs off shop variant | **Required** |
| 47 | Coupons | `admin_coupon_list_screen` | S | Shop coupons; platform coupons separate | **Required** |
| 48 | Bulk catalogue import (preview / commit / history / problem report) | `catalog/importer/*` | M + S | Per-shop offer import; platform catalogue import | **Required** |
| 49 | Delivery workers: hire, pause, remove, credentials | `admin_workers_screen`, `worker/*` | S | Roster owned by shop | **Required** |
| 50 | Territories: zones, subzones, polygons, partners | `admin_territories_screen`, `territory/*` | P + S | Geography platform-level; assignment shop-level | **Required** |
| 51 | Delivery pricing settings | `admin_delivery_pricing_screen`, singleton `id=1` | S | One row per shop | **Required** |
| 52 | Customers list | `admin_customers_screen` | P + S | **Merchant sees only customers who ordered from their shop (§80)** | **Required** |
| 53 | Customer detail file (addresses, cart, wishlist, orders, engagement, rider conduct) | `AdminCustomerDetailService` | P + S | **Heavily restricted for merchants (§79 minimisation)** | **Required** |
| 54 | Customer's order list | `admin_customer_orders_screen` | S | Only that shop's orders | **Required** |
| 55 | Review moderation | `admin_reviews_screen` | P + S | Shop moderates its own; platform arbitrates | **Required** |
| 56 | Broadcast to customers | `admin_broadcast_screen` | P + S | Shop → own customers; platform → all | **Required** |
| 57 | Analytics | `admin_analytics_screen` | S + P | Shop analytics + platform analytics | **Required** |
| 58 | Audit log | `admin_audit_log_screen`, `audit_logs` | M + P | Actor gains shop scope; platform log separate | **Required** |
| 59 | Voice order announcements | `admin_voice_settings_screen` | S | Per shop device setting | Pending |
| 60 | Receipt printer | `admin_printer_settings_screen` | S | Per shop device setting | Pending |
| 61 | Invoices | `InvoiceController`, `invoices` | S | Seller identity = the shop | **Required** |

## Worker features

| # | Current feature | Current location | Future scope | Migration required | Status |
|---:|---|---|---|---|---|
| 62 | Own login (email + password) | `WorkerAuthService`, `delivery_partners` | W | Worker bound to a shop | **Required** |
| 63 | Active tasks | `WorkerController` | W | Only their shop's orders | **Required** |
| 64 | Order detail (worker view) | `WorkerOrderView` | W | Ownership check on shop + assignment | **Required** |
| 65 | QR / pack-code scan | `WorkerScanService`, `order_scan_events` | W | Scoped | **Required** |
| 66 | Delivery status transitions | `DeliveryController` | W | Scoped | **Required** |
| 67 | GPS foreground reporting | worker app + `delivery_partners` | W | None | No change |
| 68 | COD collection, cash/UPI split | `completeCodPayment` | W | Mechanics unchanged — it is already the shop's money | Pending |
| 69 | Customer rating (1–10) | `customer_delivery_ratings` | W + S | Visible to the rating shop; platform aggregate | Pending |

## Platform / operations features

| # | Current feature | Current location | Future scope | Migration required | Status |
|---:|---|---|---|---|---|
| 70 | Outbox worker | `outbox_events` | P | None | No change |
| 71 | Idempotency records | `idempotency_records` | P | None | No change |
| 72 | Distributed lock | `shedlock` | P | None | No change |
| 73 | Health / ready / live / version | `HealthController` | P | None | No change |
| 74 | Off-box backup tracking | `ops_backup_runs` | P | None | No change |
| 75 | Money alerts, stuck-refund chase | `StuckRefundsGetChased`, alert workflows | P | Scope alerts by shop | Pending |
| 76 | TLS expiry check | `OpsStatusController` | P | None | No change |
| 77 | Rate limiting (Redis) | `config/RateLimit*` | P | Consider per-merchant quotas | Pending |
| 78 | Caching (14 names) | `CacheConfig` | P + S | **Tenant-key every shop-scoped cache** | **Required** |
| 79 | Image upload, R2 staging + sweep | `upload/*` | P | Shop-scoped folders | Pending |
| 80 | Search synonyms | `search_synonyms` | P | None | No change |
| 81 | Presence / concurrent shoppers | `presence/*` | P + S | Per shop | Pending |
| 82 | Geocoding | `geo/*` | P | None | No change |

**Nothing is dropped.** 82 features; 4 blocked on decision W1 (money model); the rest
map to a level.

---

# PHASE 2 — Architecture plan

## 2.1 Tenancy model

Shared database, shared schema, **discriminator column** (`shop_id`), with isolation
enforced at the data-access layer rather than by per-query discipline.

Why not a database or schema per shop: 48 tables × N shops makes migrations
combinatorial, cross-shop search impossible without federation, and the existing
Flyway/`validate` discipline unusable. §3 explicitly warns against it. Row-level
tenancy scales to "a town, then towns, then cities" — §1's actual requirement — and
does not encode any maximum shop count.

```
platform
  └── merchant            (owner identity, KYC, lifecycle status)
        └── shop          (storefront, geo, hours, radius, payout, settings)   1:N
              ├── shop_product_variant   (price, cost, stock, availability, offers)
              ├── inventory              (1:1 with shop_product_variant)
              ├── orders                 (shop_id)
              ├── delivery_partners      (shop_id)
              └── settings rows          (ops, delivery pricing — one per shop)

central catalogue (platform)   product ── product_variant ── product_image
                               category (taxonomy)
customer identity (platform)   one account, orders across all shops
geography (platform)           country → state → city → locality → service_area
```

### The isolation mechanism (§6, §78)

Three layers, defence in depth. The third is the one that actually holds:

1. **Route** — `SecurityConfig` continues to gate on permission.
2. **Service** — explicit ownership assertion, as `getOwnedOrderDetail` already does.
3. **Data access — the load-bearing layer.** A Hibernate filter (or an
   `@Where`-equivalent applied from a request-scoped `TenantContext`) adds
   `shop_id = :currentShop` to every query against a shop-owned entity, **by
   default**. Reaching outside requires an explicit, audited platform-admin escape.

Layer 3 matters because there are 269 repository methods. Any approach that depends
on a developer remembering a predicate will leak — not if, when. Default-deny inverts
that: forgetting yields *no rows*, not *someone else's rows*.

**The tenant never comes from the request.** `shop_id` in a path or body is a
*selector among shops the credential already permits*, never the grant itself — which
is exactly the rule the customer cart path already follows (Trace 1).

## 2.2 Roles (§77)

Existing roles are preserved and given a scope, rather than replaced:

| §77 conceptual role | Maps to today | Becomes |
|---|---|---|
| `CUSTOMER` | `Role.CUSTOMER` | unchanged, platform-level |
| `MERCHANT_OWNER` | `SUPER_ADMIN` | merchant-scoped; all their shops |
| `SHOP_ADMIN` | `ADMIN`, `MANAGER` | shop-scoped |
| — | `INVENTORY_MANAGER`, `ORDER_MANAGER`, `DELIVERY_MANAGER`, `SUPPORT` | shop-scoped staff roles, kept as-is |
| `SHOP_WORKER` | `DELIVERY_BOY` | shop-scoped |
| `PLATFORM_ADMIN` | *(new)* | platform-scoped; `SYSTEM_ADMIN` splits into platform vs shop system permissions |

Authorization becomes **permission × scope**. `CATALOG_MANAGE` stops meaning "may edit
products" and starts meaning "may edit products *of shops in scope*". The 18
permissions and the Java→Dart drift test survive intact.

## 2.3 Catalogue split (§10, §11)

```
BEFORE                                AFTER
product                               product                      (P: identity)
  └─ product_variant                    └─ product_variant         (P: 250g/1kg/5kg + barcode)
       ├─ selling_price  ✗ shop data         └─ shop_product_variant (S)
       ├─ mrp            ✗ shop data              ├─ selling_price
       ├─ cost_price     ✗ shop data              ├─ mrp / cost_price
       └─ inventory (1:1)                         ├─ available, offers
                                                   └─ inventory (1:1)
```

Variants stay on the catalogue product (§11) — a 1 kg pack is a property of the
product, not of the shop. Only *commercial* attributes move down.

Migration is by **expand → dual-write → contract**: the old columns stay readable
until every reader has moved, so a rollback at any point is a config change.

## 2.4 Order model (§16)

```
order_group  (customer-facing: one checkout, one payment intent, one idempotency key)
   ├── order  shop_id=A   own status · delivery · worker · charges · refunds · invoice
   ├── order  shop_id=B   ...
   └── order  shop_id=C   ...
```

`placeOrder` partitions the cart by `shop_id`, then runs the existing per-order logic
once per group member — reusing the state machine, the fixed lock order, the atomic
inventory decrement and the outbox, all unchanged. Shop A never sees group siblings.

Delivery fee, coupon application and minimum-order rules are evaluated **per shop
order**, because they are the shop's terms.

## 2.5 Search (§81–83)

A standalone, testable service — never inside a UI function:

```java
SearchResult search(String query, CustomerLocation at, SearchPolicy policy)
```

Deterministic pipeline: resolve serviceable shops at the customer's location → match
the query against the *central catalogue* → collect each shop's offer → if fewer than
`policy.minResults`, widen the radius in configured steps and record that it widened →
score → rank → attach a reason to every result.

Scoring starts **transparent and configurable**, not clever (§82): relevance,
distance, total cost to the customer (item + delivery), availability confidence,
delivery time, local preference. Weights live in a `SearchPolicy` config object, not
in code constants. No ML in version 1.

Every result carries a machine-readable reason so §83's strings are generated, not
hand-written per screen: `Nearby` · `2.4 km away` · `Fast delivery` ·
`Lower total price` · `Showing results from 20 km because no nearby shop has this`.

**Fairness (§19, §20):** ranking may not be influenced by merchant payment. No
minimum resale price is implemented (§20 forbids it). Integrity controls are
*detection + policy action with a recorded reason*, never silent demotion.

## 2.6 Geography (§88, §89)

```
country → state → city → locality → service_area
```

A shop declares serviceability by the **simplest reliable means first**: an origin
point plus a delivery radius, optionally refined by the polygon subzones the territory
engine already supports. Onboarding a new town becomes inserting locality rows and a
shop — data, not code (§88).

The existing `delivery_zones` / `delivery_subzones` become platform geography; shops
attach to them.

## 2.7 Merchant lifecycle (§90) and retention (§91)

`APPLICATION → PENDING_REVIEW → VERIFICATION_REQUIRED → APPROVED → ACTIVE`
with `SUSPENDED`, `REJECTED`, `REMOVED` as terminal or reversible states.

Every transition writes an audit row with an actor, a reason code and free text (§21).
Nothing is hard-deleted: merchants, shops, orders, payments and financial records use
`active` / `deleted_at` / `status`, consistent with the existing `active` flags (§91).

## 2.8 Platform modes (§2)

```
platform.mode = SINGLE_SHOP | MULTI_SHOP_DEMO | MULTI_SHOP_PRODUCTION
```

One codebase, one schema, one deployment. `SINGLE_SHOP` resolves the tenant to Shop #1
implicitly so today's APKs keep working unchanged; the other two require an explicit
shop context. Demo merchants are **real rows flagged `is_demo`** running the real
architecture (§22, §23) — never a separate code path.

---

# PHASE 3 — Migration plan

## 3.1 Slice sequence

Each slice ships independently, keeps CI green, and is reversible on its own.

| Slice | What | Reversible by | Blocked by |
|---|---|---|---|
| **0** | `merchants`, `shops`; Shop #1 from `STORE_*`; nullable `shop_id` on shop-owned tables, backfilled; `platform.mode` flag. **Nothing reads it.** | Dropping unread columns | — |
| 1 | `TenantContext` + Hibernate filter; staff JWT gains a shop claim; ownership assertions. Still one shop. | Flag off | — |
| 2 | Cross-tenant leak test suite (§78) as a standing CI category | — | Slice 1 |
| 3 | Merchant/shop CRUD, lifecycle, Platform Admin API | — | Slice 1 |
| 4 | Catalogue split: `shop_product_variant`, expand → dual-write → contract | Reading the old columns again | Slice 1 |
| 5 | Per-shop settings: ops, delivery pricing, hours, roster | — | Slice 1 |
| 6 | `order_group`; cart lines gain shop; checkout splits | — | Slice 4 |
| 7 | Search service, geography, serviceability | — | Slice 4 |
| 8 | Merchant app shop switcher; Platform Admin app | — | Slice 3 |
| 9 | Demo merchant seeding through the real architecture | — | Slice 6 |
| 10 | Per-shop VPA, refund obligations, invoicing | — | W1 decided; much smaller than planned |

Slice 10 is deliberately last: it is the only slice that touches a live payment path.

## 3.2 Migration safety (§92)

Every tenancy migration follows the same shape, in **separate** Flyway versions:

```
1. add column NULL           ← deployable alone, zero risk
2. backfill                  ← idempotent, batched, re-runnable
3. verify                    ← row counts + orphan check, FAILS the migration if wrong
4. add NOT NULL + FK + index ← only after step 3 passes
```

Verification is a migration step, not a manual afterthought — §92's "never assume a
successful migration command means the data is correct" is enforced by making the
migration itself fail when counts disagree.

> **The Flyway trap this must avoid.** `FlywayAfterSchemaConfig` runs Flyway *after*
> Hibernate schema generation, so in a local bootstrap the migration SQL may never
> execute and its errors stay hidden until CI. This already bit V45 (`CHAR(64)` vs
> `varchar`). Every tenancy migration must be exercised on a Flyway-first path before
> it is pushed.

Backups: the deploy pipeline already dispatches and verifies an off-box backup per
deploy. A cutover deploy must confirm the backup **before** the migration runs, not
after.

## 3.3 Backward compatibility (§96)

- Existing customer APKs keep working: their tokens carry no shop claim, and
  `SINGLE_SHOP` resolves the tenant implicitly.
- Existing endpoints keep their paths and shapes. Shop-scoped variants are **added**
  (`?shopId=`, or a shop-scoped route family), never substituted.
- Response DTOs gain fields; they do not lose or retype them — the same additive rule
  that let `variantCount` ship without breaking older builds.
- Redis: cache keys gain a tenant prefix, so old entries simply miss rather than
  serving the wrong shop. Flush at cutover regardless.

## 3.4 Rollback

Slices 0–5 roll back by turning `platform.mode` back to `SINGLE_SHOP` and, if
necessary, dropping columns nothing reads. Slices 6+ roll back by redeploying the
previous image, which the deploy script already keeps (`keep gp-store-backend:<sha>
(running or rollback)`).

The point at which rollback stops being free is Slice 4's *contract* step — dropping
the old price columns. That step is deliberately separated from the rest of Slice 4 by
at least one full deploy cycle.

---

# What is NOT decided here

W1 is answered (`03-decision-w1-money-model.md`) and no feature is Blocked any more.
Four questions remain:

1. ~~**Money model**~~ — **DECIDED**: merchants collect directly; GP-STORE is the
   system of record. See `03-decision-w1-money-model.md`.
2. Commission model — now constrained to invoice / subscription / none
3. Order numbering: global or per-shop
4. One rider per shop, or shared
5. Shop #1's real merchant and shop name

Slice 0 depends on none of them, which is why it is safe to start.

---

# Slice 1 addendum — why enforcement is NOT `@TenantId`

Hibernate 6 ships `@TenantId`, which is the obvious tool for discriminator
multi-tenancy: annotate the field, provide a `CurrentTenantIdentifierResolver`, and
Hibernate adds `shop_id = ?` to every query and sets it on every insert. It was the
first design considered for Slice 1's enforcement, and it is the wrong one here.

**`@TenantId` has no way to step outside the tenant.** The resolver returns one value
per session and Hibernate applies it unconditionally. That is exactly right for an
application where every unit of work belongs to one tenant — and this application has
several that legitimately do not:

| Work | Why it spans shops |
|---|---|
| `OutboxWorker` | drains events for every shop in one pass |
| Stuck-refund sweep | chases money across the marketplace |
| Late-delivery flagger | scans all deliveries against their promised times |
| UPI/online payment expiry | ages out unpaid payments everywhere |
| R2 staging sweep, backup checks | platform housekeeping |
| Platform Admin | the whole point of the role |

Under `@TenantId` each of those would silently see one shop's rows. Not fail — *see
less*, which is the failure mode that gets discovered by a shopkeeper asking why their
refund was never chased.

**So enforcement uses Hibernate `@Filter`, which can be enabled per session.** A
request enables it with the resolved shop; platform work does not enable it at all,
and says so at the call site through `TenantScope.platform()`. The unscoped case
becomes something a reader can see, rather than something Hibernate does invisibly.

`@Filter` also works without a mapped field — its condition is raw SQL against
`shop_id` — which means Slice 0's SQL-only columns need no entity changes to be
enforced.

**Two limits of `@Filter` that Slice 2's leak tests must cover, because the tool will
not:**

1. **Native queries are not filtered.** `@Query(nativeQuery = true)` bypasses filters
   entirely. The repository layer has 97 `@Query` methods; every native one needs its
   predicate written by hand, and a test that proves it.
2. **`find()` by primary key is not filtered.** Loading an entity by id goes through
   the persistence context, not a filtered query. Ownership on a by-id load has to be
   asserted in the service — which is what `getOwnedOrderDetail` already does, and is
   the pattern to extend.

Neither is a reason to avoid `@Filter`; both are reasons the leak tests are a slice of
their own rather than an afterthought.

---

# Slice 1 — what shipped, and what it does not yet cover

*Written after the code, from the test results, not from the plan.*

## The four mechanisms

| | What it does | Where |
|---|---|---|
| **Resolution** | Turns a credential into a `TenantScope`, never a request field | `TenantResolver`, `TenantContextFilter` |
| **Reads** | Hibernate `@Filter` enabled on every session opened inside a shop scope | `TenantFilterActivator`, `@Filter` on 12 entities |
| **By-id reads** | `@PostLoad` refuses any shop-owned row from another shop | `TenantEntityListener.assertOwnership` |
| **Writes** | `@PrePersist` stamps the shop from the scope, overriding whatever the object carried | `TenantEntityListener.stampShop` |

**The filter had to be enabled at the `EntityManagerFactory`, not in the servlet
filter.** `spring.jpa.open-in-view=false`, so the Hibernate session is opened per
*transaction* — by the time a request reaches `TenantContextFilter` there is no session
to enable anything on, and by the time there is one the filter has finished. Wrapping
the factory catches every session the application opens: the transaction manager's, the
`@PersistenceContext` proxy's, and any opened directly.

**The `@PostLoad` check is the one that stops id manipulation.** A filter rewrites
queries; `findById` is not a query. The entity listener closes that, and closes
association traversal with it, which is why "change the id in the URL" answers 404 in
`CrossTenantApiAccessTest` rather than 200.

**Refusals are 404, never 403.** A 403 on a guessed id confirms the id exists;
alternating 403 and 404 down a range maps out a competitor's order volume.

## The 12 shop-owned entities

`Order`, `Payment`, `Delivery`, `DeliveryBatch`, `DeliveryPartner`, `Invoice`,
`OrderReturn`, `Coupon`, `Inventory`, `CatalogImportRun`, `OrderScanEvent`,
`CustomerDeliveryRating`.

`V47` removed the `shop_id` column defaults V46 had left behind — a default answers
"which shop" without anyone deciding, and answers it wrongly the moment a second
merchant exists — and added a `shop_id` index to each of those tables.

## What Slice 1 does NOT cover

| Gap | Why it is open | Closes in |
|---|---|---|
| **`store_operations_settings`, `delivery_pricing_settings`** | Single-row settings loaded by `findById(SINGLETON_ID)`; a filter does not apply to a load by primary key. Making them per-shop needs the singleton itself split, not a class annotation. They keep their `shop_id` default until then | Slice 5 |
| **`OrderRepository.revenueByDayBetween`** | Native SQL, so unfiltered. Under a marketplace it would total every shop's takings into one dashboard line. Correct today under SINGLE_SHOP | Reporting slice |
| **`InventoryRepository.decrementIfAvailable`** | Bulk JPQL update, which Hibernate does not filter. Safe today because inventory is one row per *variant*, so there is exactly one row it can reach | Slice 4 (shop offerings) |
| **Products** | The central catalogue has no `shop_id` by design (§10). There is no "Shop A's product" to isolate until `shop_product_variant` exists | Slice 4 |
| **Payment webhooks** | `TenantContextFilter` skips `/api/payments/webhooks/**` — Cashfree arrives with a signature, not a session. Runs unscoped, which is right today and needs an explicit platform scope under a marketplace | Slice 10 |
| **Staff tokens carry no shop claim** | Under `MULTI_SHOP_*`, `TenantResolver` refuses any credential that is not `SYSTEM_ADMIN`. Deliberate: inventing a shop for a token nobody scoped is inventing an authorization | Slice 3 |

`ShopScopeIsNotOptionalTest` asserts each of these by name, so an entry that is quietly
fixed or quietly added fails the build rather than sitting in a document.

---

# Slices 4 and 5 — the shop-product architecture, and the five named gaps

*Written after the code, from the test results.*

## The product model

```
products / product_variants          ONE row per real-world item, shared.
        |                            Identity, pack size, barcode, photo, GST class.
        |
shop_product_variants                ONE row per (shop, variant).
        |                            Whether this shop sells it, at what price,
        |                            at what cost, listed or not, its shelf order.
        |
inventory                            ONE row per (shop, variant).
                                     How many units this shop has right now.
```

**The catalogue is not copied per shop.** Two kiranas selling Aashirvaad atta 5 kg point
at the same `product_variants` row. Adding Shop N adds rows — never tables, never code,
never a branch on which shop this is. `CrossTenantShopCatalogTest.shopNAddsNoCode`
creates a third shop through the identical call the first two used and asserts the other
two are unaffected.

**Price and stock are keyed alike but stored apart, on purpose.** They have opposite
write patterns: a price is edited by a person, occasionally; stock is decremented under a
row lock on every checkout. One row would put a price edit in contention with live orders.

**`product_variants.selling_price` did not become dead weight** — it is now the
*catalogue default*, the terms a shop starts from when it begins stocking an item. That
is what makes onboarding Shop N one INSERT rather than a data-entry exercise.

### What changed on the money path

Every price a customer sees or is charged now reads the shop's own listing: add-to-cart,
cart display, checkout preview, the order total, each order line, the admin's manual
order line, and both free-delivery profit calculations. Under `SINGLE_SHOP` the listing
and the catalogue default are equal by construction — V48 backfilled them, every write
path maintains both, and `ShopCatalogReconciliation` re-lists anything a new code path
misses at startup — so nothing a customer sees changes today.

### The cache was a cross-shop leak, and is the part that would have been missed

Ten catalogue caches were keyed on their method arguments alone — `getAllProducts(page,
size)`, `productDetail(id)`, `trending(days, limit)`. The moment a price is per-shop, the
first shop to ask for page 1 fills the entry and **every other shop is served its prices,
with no query run and nothing in any log**. The Hibernate filter cannot help: the query
never happens. `CacheConfig.keyGenerator` now prefixes every key with the tenant scope,
and `RecommendationHygieneTest` asserts the shop-free key *misses*.

## The five gaps from the Slice 1 report, closed

| Gap | What it is now |
|---|---|
| `revenueByDayBetween` | Takes a `shopId` written into the native SQL by hand, read off the tenant scope by `AnalyticsService`. `getSalesSeries` has no shop parameter — asserted by reflection, so one cannot be added quietly |
| `decrementIfAvailable` | Carries `and i.shopId = :shopId`, from the scope. Without it, one customer buying one packet took a unit off **every** merchant stocking that variant |
| `store_operations_settings` | One row per shop (V49). The `CHECK (id = 1)` is gone, the id is database-generated, and the services find the row by shop |
| `delivery_pricing_settings` | The same — and `save()` no longer takes the id from the request body, which was an id-manipulation route into another shop's pricing that no filter could see |
| Payment webhooks | Given `TenantScope.platform()` **explicitly** rather than left unscoped. Identical reads, but an unscoped thread anywhere else is now a bug rather than a maybe |

One more found while doing it: the inventory restore in the payment-expiry sweep looked
up stock by variant alone, from a thread with no filter — free to lock and credit
whichever shop's row it found first. It now reads the shop off the **order** being
restored.

## What is still not protected

| Gap | Why | Closes in |
|---|---|---|
| **The central catalogue is writable by any shop admin** | `products` and `product_variants` have no `shop_id` by design (§10), so a merchant editing a product's name or the catalogue default price changes it for every shop that sells it. Under one shop this is simply "the shopkeeper edits their catalogue" | Slice 3 — catalogue writes become platform-admin or moderated |
| **Staff tokens carry no shop claim** | Under `MULTI_SHOP_*`, `TenantResolver` refuses any credential that is not `SYSTEM_ADMIN`. Deliberate: inventing a shop for an unscoped token invents an authorization | Slice 3 |
| **Multi-shop carts** | A basket spanning two shops must split into an order group and N shop orders (§16). Checkout today refuses a line the current shop does not list, which is correct but is not the split | Slice 6 |
| **Raw SQL bypasses the stamp** | `@PrePersist` cannot see a `JdbcTemplate` insert. No production code does one into a shop-owned table (verified); test fixtures do, which is how unowned rows appear in the test database | — (guarded by `ShopScopeIsNotOptionalTest`) |
| **`ShopCatalog`'s catalogue fallback** | Under `SINGLE_SHOP` only, an unlisted-but-priced variant is still sold at the catalogue price, with a warning logged. There is no fallback under a marketplace | — (deliberate) |
