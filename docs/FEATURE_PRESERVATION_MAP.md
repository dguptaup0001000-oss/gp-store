# Feature preservation map

Required by Part 1 §1.4. This is the inventory the multi-merchant work is
measured against: every capability GP-STORE had as a single shop, where it
lives now, and what the marketplace transformation did to it.

**Nothing in the "single shop" column may stop working.** Where a feature
needed architectural change it was migrated, never deleted (§19).

## How to read the status column

| Status | Meaning |
|---|---|
| **shop-scoped** | The data is shop-owned: filtered on read, stamped on write, and cross-shop access is refused server-side. |
| **central** | Deliberately shared by the whole marketplace (§6: one catalogue). |
| **platform** | Belongs to GP-STORE itself, not to any shop. |
| **deployment-wide** | Still one value for the whole deployment. Listed with whether that is correct or outstanding. |

## Customer-facing

| Feature | Where it lives | Status | Notes |
|---|---|---|---|
| Registration, login, JWT, refresh | `auth/`, `security/` | platform | One account across every shop (§5). Customer identity is never duplicated per shop. |
| Addresses | `address/` | platform | `/api/addresses` runs platform-scoped: a customer needs an address before they have a shop. |
| Shop discovery | `platform/ShopDiscovery`, `MarketplaceController` | platform | Nearest-first within each shop's own radius, plus explicit "search farther". |
| Product browsing (feed) | `ProductService.browseAll` | shop-scoped | Narrowed to the shop's shelf. |
| Categories | `CategoryService` | central | Category rows are catalogue, not shelf. |
| Brands and brand counts | `ProductRepository.findBrandsWithProductCounts` | shop-scoped | Counts only what this shop lists. |
| Search (instant, smart, trigram + ILIKE) | `ProductBrowseRepository.searchInstant`, `SmartSearchService` | shop-scoped | Both ranking paths ask the shelf question. |
| Bestsellers collage | `ProductBrowseRepository.findBestsellerTiles` | shop-scoped | Tiles, thumbnails and the "+N more" count. |
| New arrivals | `ProductService.getNewArrivals` | shop-scoped | |
| Category browse + sort/filter | `ProductBrowseRepository.browse` | shop-scoped | Price, discount and in-stock aggregates read the shop's own listings. |
| Product detail, gallery, 3D model | `ProductService.getProductById` | shop-scoped | 404 for a product this shop does not list. |
| Recommendations (trending, bought-together, buy-again) | `RecommendationService` | shop-scoped | Built from this shop's order history, filtered to its current shelf. |
| Reviews and ratings | `engagement/` | central | A review is about the product, not the shop that sold it. Stated, not accidental. |
| Wishlist | `service/WishlistService` | platform | Belongs to the customer. |
| Cart | `service/CartService` | shop-scoped lines | One basket may hold lines from several shops (§16 of the master spec). |
| Coupons / offers | `entity/Coupon` | shop-scoped | |
| Checkout | `OrderService`, `ordergroup/` | shop-scoped | A multi-shop basket becomes one order group and N shop orders. |
| Payments (COD + Cashfree) | `payment/` | shop-scoped rows, **deployment-wide gateway** | See "Outstanding" below. |
| Refunds, partial refunds | `payment/`, `returns/` | shop-scoped | |
| Returns | `returns/` | shop-scoped | |
| Order history | `OrderService`, `OrderGroupService` | shop-scoped | Every order names the shop it came from (§5). |
| Delivery tracking | `delivery/` | shop-scoped | |
| Notifications (FCM) | `service/NotificationService` | shop-scoped | |
| Store status banner | `StoreStatusController` | shop-scoped | Now reports the shop's own hours and a timed pause. |
| Out-of-stock state (§7 STATE 2) | `ShopStock`, `VariantResponse.inStock` | shop-scoped | Listed-but-empty shows, says so, hides the price, cannot be added. |
| Shop logo, badge, policies | `shops.logo_url`, `shop_policies` | shop-scoped | On the storefront detail. |
| Trusted badge | `ShopReliability` | shop-scoped, **computed** | No column, no setter, no route — §10's "not purchasable". |

## Merchant-facing

| Feature | Where it lives | Status | Notes |
|---|---|---|---|
| Shop profile | `ShopSelfServiceController` | shop-scoped | Shop comes from the credential, never a request. |
| Shop status transitions | `ShopLifecycleService` | shop-scoped | |
| Price list / listings | `ShopProductVariant`, `/api/shop/listings` | shop-scoped | Per-shop price, MRP, availability. |
| Inventory | `entity/Inventory` | shop-scoped | |
| Bulk catalogue import | `catalog/importer/` | shop-scoped | |
| Orders board | `OrderService` admin views | shop-scoped | |
| Earnings | `money/ShopEarnings` | shop-scoped | No commission arithmetic (W2 undecided). |
| Readiness checklist | `ShopReadiness` | shop-scoped | |
| Staff | `ShopStaff`, `ShopMembership` | shop-scoped | |
| Riders | `entity/DeliveryPartner` | shop-scoped | Per shop, no shared pool (W4). |
| Territories / zones / subzones | `territory/` | shop-scoped | |
| Delivery pricing | `DeliveryPricingSettings` | shop-scoped | |
| Order acceptance switch | `StoreOperationsSettings` | shop-scoped | AUTO / ON / OFF. |
| Timed pause | `StoreOperationsSettings.pausedUntil` | shop-scoped | Added in Part 1. |
| Closed days | `entity/StoreClosure` | shop-scoped | Per shop since V54. |
| **Trading hours** | `shop_business_hours`, `shop_hours_override` | shop-scoped | Added in Part 1 — was deployment-wide. |
| Shop switcher | `/api/shop/my-shops` | shop-scoped | Lists grants that already exist; naming anything else is refused. |
| Policies (delivery / cancellation / returns) | `shop_policies` | shop-scoped | Empty body removes rather than storing an empty promise. |
| Business identity (GSTIN, FSSAI) | `shops` | shop-scoped | Per premises, not per merchant. |
| Own trading record | `/api/shop/reliability` | shop-scoped | Includes what stands between the shop and TRUSTED. |
| Where the money goes | `/api/shop/payment-collection` | deployment-wide, **stated** | See below. |
| Morning preparation list | `StoreAdminController` | shop-scoped | Reads the shop's own first run. |

## Worker-facing

| Feature | Where it lives | Status | Notes |
|---|---|---|---|
| Rider login (password, Gmail) | `worker/`, `/api/worker/auth/` | platform route, shop-scoped identity | The roster row carries the shop. |
| My assignments | `delivery/` | shop-scoped | |
| Order detail, pack scan, pack code | `worker/` | shop-scoped | |
| Delivery status machine | `delivery/` | shop-scoped | |
| Duty toggle, location reporting | `worker/`, `presence/` | shop-scoped | |
| Customer call | Flutter worker screens | — | |

## Platform-admin-facing

| Feature | Where it lives | Status | Notes |
|---|---|---|---|
| Merchant lifecycle | `MerchantLifecycleService` | platform | APPLICATION → PENDING_REVIEW → APPROVED → ACTIVE (§9). |
| Shop verification (§10) | `ShopLifecycleService.verify` | platform | NONE / VERIFIED / BUSINESS_VERIFIED. The **only** route that grants one. |
| Shop lifecycle | `ShopLifecycleService` | platform | |
| Market overview | `PlatformMerchantController` | platform | |
| Catalogue definition | `CatalogDefinitionAuthorization` | platform | Only the platform defines central products. |
| Ops status, monitoring | `monitoring/` | platform | |
| Customer 360 | `controller/` | platform | |

## Added after the first map was written

*These are the subsystems Parts 3 and 4 asked for. They are listed here for the
same reason everything above is: so that "what belongs to a shop and what
belongs to the platform" stays one answer rather than a habit.*

| Feature | Where it lives | Status | Notes |
|---|---|---|---|
| Cancellation terms (§9/§10) | `store_operations_settings` columns, `CancellationPolicy` | shop-scoped | The free window and the fee are the shop's; the ceiling is platform configuration. |
| Cancellation debts (§11) | `customer_cancellation_dues` | shop-scoped | The debt is to a shop, not to GP-STORE. A shop must not see what a customer owes its competitor. |
| Order lifecycle table (§1) | `OrderLifecycle` | central | One transition table, no per-caller `if`s. |
| Shop ratings (§17) | `shop_ratings` | shop-scoped | Service, not product. |
| Product reviews (§17) | `reviews` | central | Follows the product across every shop that sells it. |
| Rating moderation (§20) | `HideReason`, `ReviewService.hideReview` | platform | Hidden, never deleted; closed reason list. |
| Billing plans and ledger | `billing/` | platform | Append-only, enforced by a trigger. No commercial amount in the code. |
| Merchant governance (§2) | `merchant_governance_actions`, `MerchantGovernance` | platform | `about_shop_id`, **not** `shop_id` — it is data, not a tenancy boundary. |

## Cross-cutting

| Concern | Where it lives | Status |
|---|---|---|
| Tenant resolution | `TenantResolver`, `TenantContextFilter` | Credential only; `X-Shop-Id` may narrow, never grant. |
| Read filtering | `ShopScopeFilter` (Hibernate `@Filter`) over 20 shop-owned entities | |
| Write stamping | `TenantEntityListener` | |
| PK-load ownership guard | `TenantEntityListener` `@PostLoad` | Closes the `find()`-by-id blind spot. |
| Native-SQL narrowing | `catalog/shop/Storefront` | Closes the `@Filter` blind spot. |
| Caching | `CacheConfig.keyGenerator` — every key carries the shop | |
| Cache invalidation on shelf change | `ShopShelfCache` | |
| Background jobs | `BackgroundWorkScope` | |

## Outstanding — deployment-wide settings that are not yet per shop

| Setting | Where | Correct as-is? |
|---|---|---|
| Payment gateway credentials (`cashfree.*`) | `CashfreeProperties` | **No, and now said out loud.** One account collects for every shop, which makes GP-STORE a payment aggregator. §17 forbids inventing per-merchant accounts on an architectural preference, so `PaymentCollection` is the boundary: the question is asked per shop, the merchant is told the answer, and configuring `MERCHANT_COLLECTS` without an implementation fails at boot rather than misreporting every settlement. The business decision is still open. |
| Morning preparation time, closing countdown, closure lookahead | `StoreScheduleProperties` | Acceptable. Operational timings, not "when is this shop open". |
| Rate limits, CORS, JWT, uploads, R2 | `config/` | Correct — platform infrastructure. |
