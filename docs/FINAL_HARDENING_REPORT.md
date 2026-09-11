# FINAL HARDENING REPORT

*Audit, fix, test and verification pass over Parts 1–4. Every number below was
produced by a command run in this session against the committed code. Where a
thing was not run, this report says so rather than estimating it.*

---

## 1. Overall status

**The backend is green, scoped, and honest about what it does not know. The
customer app has not caught up with it, and no Android artifact can be built
on this machine.**

I did not trust the brief's figures. I re-ran the baseline myself first: 1727
tests, 0 failures, SpotBugs clean — the claim held. Then I audited, and found
four real defects, all of them mine or inherited, none of them theoretical:

1. **A crash I introduced in Part 2 §10.** Hiding the price of an out-of-stock
   item made `sellingPrice` nullable on the wire, but the Dart model still
   declared it `required double`. Any out-of-stock product in a feed would have
   thrown at JSON parse time and taken the whole screen down. This was the most
   serious finding in the pass and it was self-inflicted.
2. **A decorative payment boundary.** `PaymentCollection` existed, described the
   merchant-collects case, and *nothing consulted it*. Checkout took every
   shop's money into the platform account regardless.
3. **An invented commercial number.** `CancellationPolicy` carried
   `DEFAULT_MAX_FEE_PERCENT = 5` — a rate no one approved, charged to real
   customers of real kiranas.
4. **Governance thresholds compiled in as `public static final`** — unchangeable
   without a release, on questions that are fairness decisions, not tuning.

All four are fixed. The pass ends at **1743 backend tests, 0 failures**,
**682 Flutter tests, 0 failures**, SpotBugs clean.

What is *not* true: this is not production-ready. Sections 10, 12 and 13 say
why, and the reasons are not "more tests needed".

---

## 2. Backend tests

Full `mvn clean verify` on exactly the committed code:

```
Tests run: 1743, Failures: 0, Errors: 0, Skipped: 1
BugInstance size is 0
BUILD SUCCESS
```

- 250 test classes, against a **real Postgres 16**, not H2. The Hibernate
  `@Filter` behaviour, the check constraints and the append-only audit trigger
  are all things an in-memory substitute would quietly not have.
- Baseline before this pass: 1727 / 0 failures. Net **+16 tests**.
- The 1 skipped test is pre-existing and unrelated.

**Mutation check** (the only evidence a test is load-bearing): I removed
`@Filter` from `CustomerCancellationDue` and re-ran. The named test
`TheNewSurfacesAreAlsoScopedTest$Dues.theOutstandingListIsScoped` failed. The
filter was restored from backup and the suite re-run green. A test that passes
whether or not the protection exists is not a test; this one is.

---

## 3. Flutter

Run on the project's **own CI pin** — `flutter-version: '3.35.7'` from
`.github/workflows/*.yml`, Dart 3.9.2 — not on `stable`. This matters: I first
installed `stable` (Dart 3.13.3) and `build_runner` died with
`Missing implementation of visitDotShorthandPropertyAccess`. That was a
toolchain mismatch, not a code fault, and rewriting code to satisfy it would
have been exactly the wrong move. Installing the pin fixed it (63 generated
outputs). The tooling's side-effects on `analysis_options.yaml` and
`pubspec.lock` were reverted.

```
flutter analyze  →  41 issues found — 0 errors, 0 warnings, 41 info
flutter test     →  +682: All tests passed!   (exit 0)
```

Every one of the 41 is an `info` lint (`prefer_const_constructors` and
similar). Nothing is an error or a warning.

**The CRITICAL fix.** `ProductVariant.sellingPrice` is now `double?`, with
three deliberate accessors so no call site has to guess:

```dart
bool get isBuyable => available && (inStock ?? true);
bool get hasPrice  => sellingPrice != null;
double get priceOrZero => sellingPrice ?? 0;
```

Nine call sites were corrected — product card, variant picker, product detail,
wishlist, and four admin screens. The customer-facing ones now say **"Out of
stock"** in words where a price used to be, rather than rendering `₹` followed
by nothing. `priceOrZero` exists for arithmetic only; no screen prints ₹0 for
an unpriced item.

---

## 4. Security

**A `shop_id` column is not security, so I tested it at the HTTP layer.**

New suite `TheNewSurfacesAreAlsoScopedTest` (9 tests, MockMvc, real
authentication) drives horizontal privilege escalation and IDOR against every
surface Parts 3–4 added:

| Surface | Attack tested |
|---|---|
| `/api/shop-ratings/manage`, `/respond`, `/report`, `/hide` | Shop B's owner reading and moderating Shop A's ratings |
| `/api/cancellation-dues/outstanding`, `/waive` | Reading another shop's debtors; waiving a debt that is not yours |
| `/api/shop/governance`, `/actions/{id}/appeal` | Reading another shop's disciplinary record; appealing someone else's action |
| `/api/platform/governance/actions` | A shop owner reaching the platform-wide view |

**Every test has a positive control.** A test that asserts "403" proves nothing
if the endpoint 403s for everyone; each case first shows the legitimate owner
succeeding on the same route, then shows the attacker failing. Attacks are run
both by identity (wrong owner, right id) and by id (right owner, forged id).

Existing coverage that still holds: `CrossTenantApiAccessTest` (8),
`CrossTenantDataIsolationTest` (12), `CrossTenantShopCatalogTest` (16),
`ShopScopeIsNotOptionalTest` (7) — the last one enumerates every `ShopOwned`
entity and fails if one gains a `shop_id` that nothing enforces.

Route-level authorization stays in `SecurityConfig`, never in a controller and
never in Flutter. `X-Shop-Id` may **narrow** a scope, never grant one.

---

## 5. Marketplace

Discovery, preferred shops and the price-comparison rule are **built and tested
on the backend**; `TwoWaysToFindAShopTest` covers them in 17 tests across four
nested groups (`Preferences` 8, `ProgressiveRadius` 4, `OrderingNotFiltering`
3, `TheBasket` 2), using three real shops A/B/C.

- Preferred shops: **max 2 per category**, enforced server-side. An explicit
  customer preference is **never silently overridden** because another shop is
  cheaper — the cheaper shop is *shown*, not substituted.
- Progressive radius: local-first, configurable, and **not** local-only.
- The 25% rule is applied to **final customer cost** (including delivery), not
  to the shelf price. Delivery charges are not hidden until checkout.
- **There is no pay-for-ranking anywhere in this codebase.** That is the half of
  the brief that matters most, and it is true by absence — no field, no column,
  no sort key exists that money could touch.

One §17 fix: `ShopOffers` now truncates comparison to `MAX_SHOPS_COMPARED = 20`
before its per-shop loop. A customer in a dense market was previously an
unbounded fan-out.

**The gap, stated plainly:** none of this is in the customer's hands. I audited
the app's HTTP calls. It calls `/api/marketplace/shops`, `/shops/{id}` and
`/mode`. It does **not** call `/api/marketplace/discovery`,
`/api/preferred-shops`, `/api/discovery/*`, or `/api/carts/mine/by-shop`. See
blocker 5.

---

## 6. Orders

`MultiShopCheckoutTest` — 10 tests, all green — pins the multi-shop contract:

- one order **group**, one order **per shop**, never a combined merchant order;
- items, price, delivery charge, stock, and payment all resolved per shop;
- cancellation of one shop's order leaves the other untouched;
- the checkout preview breaks the total down per shop;
- **the server sets the shop id**, and a line that arrives without a resolvable
  shop is refused rather than guessed at.

Lock ordering is unchanged and still ORDER → PAYMENT → INVENTORY and
ORDER → DELIVERY across all 11 pessimistic-write sites; durability of
post-commit side-effects is still via the outbox.

Duplicate checkout and duplicate payment callbacks are covered by the existing
idempotency tests, which I re-ran rather than re-asserted.

---

## 7. Merchant

- **Governance is a ladder, not a switch** (`ALadderNotASwitchTest`): warning →
  final warning → action, with decay, and an appeal route the merchant can
  actually reach at `/api/shop/governance`.
- **Thresholds are now configuration, not constants.** `MerchantGovernance`
  reads `governance.warning-days` / `governance.final-warning-days`;
  `ShopReliability` reads `reliability.window-days`, `reliability.min-orders`,
  `reliability.min-completion-rate`, `reliability.max-return-rate`. Unset or
  nonsensical values keep the working default — a misread property must not be
  able to withdraw the trust badge from every shop on the marketplace at once.
- **TRUSTED is computed, never granted.** It is recomputed from orders and
  returns on every read, so there is nothing to set, nothing to backfill, and
  nothing to sell. A shop that stops delivering stops being trusted the same
  week.
- **A new shop is not an untrustworthy shop**: below the minimum order count the
  answer is "not yet", never "no", and the merchant is told exactly what is
  missing.
- `StoreOperationsService.setCancellationTerms` now **refuses** fee terms while
  no platform cap is decided, and says so in words the shopkeeper can act on —
  and explicitly confirms their other settings were not changed.

**Not built, and not stubbed:** merchant-set ETA, merchant-arranged return
pickup, replacement-instead-of-refund, and two of the fee-refund eligibility
rules. A stub that always returns true is worse than a gap, because it looks
finished.

---

## 8. Payment

**This was the most important fix in the pass.** `PaymentCollection` described
who collects a shop's money and *nothing read it*. Checkout took every shop's
payment into the single platform account while the model said some merchants
collect directly. `prepareCheckout` now consults the boundary and **refuses**
rather than lie:

```java
PaymentCollection.Collector collector = paymentCollection.forShop(order.getShopId());
if (collector.merchantCollectsDirectly()) {
    throw new IllegalStateException(
            "Shop " + order.getShopId() + " is configured to collect its own payments, "
                    + "but no per-merchant collection is implemented. Refusing to take "
                    + "this payment into the platform account while reporting it as "
                    + "the merchant's.");
}
payment.setCollectionModel(collector.model());
```

Migration `V63__payments_record_who_collected.sql` adds a nullable
`payments.collection_model`. It is **deliberately not backfilled**, and the
migration's VERIFY block raises if any historical row was stamped: I do not
know how those payments were collected, and inventing an answer would corrupt
the only record of it. Every new payment records it at the moment it is taken.

What has **not** changed, on purpose:

- No provider architecture was invented. No merchant account IDs, fake or
  otherwise, went into production logic. Cashfree remains the one implemented
  provider and is not claimed to be the final business architecture.
- **No regulatory compliance is claimed.** Today one account carries every
  shop's money, which makes GP-STORE a payment aggregator in substance. That is
  a business and legal determination.
- Payment success is still never trusted from the Flutter client; the webhook
  and the provider's own record are the source of truth.

**REQUIRES BUSINESS/LEGAL/PROVIDER DECISION.** See blocker 1.

---

## 9. Billing

The billing machinery takes amounts as **data** and contains **no number**.
That is now enforced by a test rather than by discipline.

`NoInventedCommercialAmountTest` (6 tests, three nested groups):

- **`TheSource`** scans `billing` and `order/cancellation` for
  `new BigDecimal("…")` money literals (excluding `"100"`, which is percentage
  arithmetic) and fails the build if one appears. No ₹99, ₹199, ₹499, ₹999, 1%,
  2%, 5% or 10% can be reintroduced without this test going red.
- **`FailsClosed`** asserts `capIsDecided()` is false with no configuration, and
  that a shop which has stored a 2% cancellation term **charges nothing**,
  because the platform cap is undecided.
- **`StillMarked`** asserts the literal string `REQUIRES FOUNDER DECISION`
  survives in `MerchantGovernance.java`, `ShopReliability.java` and
  `CancellationPolicy.java`, and that `governance.warning-days` /
  `reliability.min-orders` are wired while
  `public static final int WARNING_DAYS` is gone.

The removed `DEFAULT_MAX_FEE_PERCENT = 5` now has **no replacement default**.
`parseCap` returns `null` for blank, malformed, or out-of-range input, and
`quote()` returns a free cancellation with the message *"This shop does not
charge for cancelling."* until a real cap is set. Failing closed on money is
the only safe direction.

---

## 10. Android build

**ENVIRONMENT BLOCKER. Not a code failure. No code was changed to work around
it, as instructed.**

Re-confirmed in this session:

```
$ curl https://dl.google.com/android/repository/repository2-3.xml
curl: (56) CONNECT tunnel failed, response 403

$ flutter build apk --release
[!] No Android SDK found. Try setting the ANDROID_HOME environment variable.
```

Note the shape of it: Flutter's **own** engine artifacts download fine, from
`storage.googleapis.com` — six `Downloading android-*` lines succeeded in under
a second each. Only `dl.google.com`, which is where the Android SDK and build
tools live, is refused by the build proxy with 403.

**Therefore: no APK and no AAB can be produced from this environment, and none
is claimed.** The Gradle configuration was not touched, because there is
nothing wrong with it.

---

## 11. Performance

**No concurrency figure is claimed, because none was demonstrated.** I did not
run load tests, and "10,000 concurrent users" or any similar number appears
nowhere in this report or in the code.

What *was* done:

- `CheckoutPerformanceTest` asserts per-request **query budgets** — a guard
  against N+1 regressions. That is a different thing from load testing and is
  not a substitute for it.
- One real unbounded path was closed: `ShopOffers` now compares at most
  `MAX_SHOPS_COMPARED = 20` shops. Before this, a customer standing in a dense
  market triggered a per-shop loop over every nearby shop.
- Cache keys remain namespaced `shop:<id>` / `platform` / `unscoped` across all
  14 cache names, so no cache entry can be served across a tenant boundary.

---

## 12. Real two-shop verification

**Verified at the backend/HTTP layer, with named tests. Not verified through
the app.** Both halves of that sentence are load-bearing.

| Concern | Test | Count |
|---|---|---|
| One group, one order per shop; per-shop items, price, delivery, stock, payment; independent cancellation; server-set shop id | `MultiShopCheckoutTest` | 10 |
| Merchant isolation and forged ids on all Part 3–4 surfaces | `TheNewSurfacesAreAlsoScopedTest` | 9 |
| Cross-tenant API access | `CrossTenantApiAccessTest` | 8 |
| Rider confined to the roster shop | `ShopStaffAndRidersTest` | 8 |
| Platform admin sees every shop; shop owner refused the overview | `MarketplaceOversightTest` | 5 |
| Three real shops A/B/C through discovery, preferences, radius, basket | `TwoWaysToFindAShopTest` | 17 |

**What was NOT done:** the live customer journey through the Flutter app
against a `MULTI_SHOP_PRODUCTION` backend — registration → address → discover
both shops → Shop A's shelf only → Shop B's shelf only → one basket → two shop
orders → history → merchant view → worker view. Nobody drove the app through
it in this session. It is listed as blocker 5, and it should be done before
release regardless of how green the backend is.

---

## 13. Remaining blockers

Numbered, each classified.

**1. Payment collection model is undecided, and the platform currently collects
everything.** — **FOUNDER DECISION / CRITICAL**
One account carries every shop's money. The seam is now real and refuses to lie
(§8), but the answer behind it — direct-to-merchant settlement, split
settlement, or aggregator with the licensing that implies — is a business,
legal and provider decision. This is the one structural incompleteness in the
system. *Requires business/legal/provider decision.*

**2. No commercial amounts exist.** — **FOUNDER DECISION**
Tiers, weekly fees, commission rates, and the cancellation-fee ceiling are all
unset. Cancellation is therefore **free for everyone** and merchants are refused
when they try to set fee terms. Nothing is broken; nothing is decided. One
property (`platform.cancellation.max-fee-percent`) turns the last one on.

**3. Governance and reliability thresholds are defensible defaults, not
approved ones.** — **FOUNDER DECISION**
90-day warning decay, 180-day final warning, 25 orders / 92% completion / 8%
returns for TRUSTED. They decide whether a real kirana carries a badge
customers read as "safe to buy from". All six are now configurable without a
release.

**4. No Android artifact can be built here.** — **ENVIRONMENT BLOCKER**
`dl.google.com` → 403 through the build proxy (§10). Code untouched. Needs a
machine or CI runner with Android SDK access.

**5. The customer app has not caught up with the marketplace backend.** —
**HIGH**
Preferred shops, Best Deal, the compare/25% surface, the progressive-radius
message and per-shop cart totals are all built and tested server-side, and the
app calls none of them. Customers cannot use features that exist. This is the
largest remaining piece of ordinary work.

**6. The live two-shop Flutter journey is unverified.** — **HIGH**
§12. Backend contracts changed under it during Parts 2–4.

**7. No load testing was performed.** — **MEDIUM**
Query budgets are asserted; throughput and concurrency are not measured. No
capacity claim should be made until they are.

**8. Fresh-database bootstrap covering V59–V63 is unexercised.** — **MEDIUM**
The migrations ran forward against the existing test database and their VERIFY
blocks passed. The empty-database path (`DDL_AUTO=update`, then a second boot
under `validate`) has not been run for these five.

**9. Merchant ETA, return pickup, replacement-instead-of-refund, refund
enforcement, and two fee-refund eligibility rules are not built.** — **MEDIUM**
Deliberately not stubbed. Note the constraint this sits inside: GP-STORE does
**not** control merchant delivery operations, so there is no centralized
dispatch, no cross-merchant worker reassignment, no automatic worker sharing,
and no GP-STORE-controlled exact ETA — by design, not by omission.

**10. A merchant is not notified of a governance action.** — **LOW**
It is recorded and readable at `/api/shop/governance`. There is no merchant
push or email channel in this system, so a shopkeeper finds out by looking.

**11. Two pre-existing test flakes.** — **LOW**
`OpsStatusServiceTest.diskOnARealDirectoryIsHealthy` fails whenever the 2.4 GB
Flutter SDK is resident (it pushes free disk under the asserted 10% floor), so
backend and Flutter cannot be verified in one invocation on this box.
`LazySerialisationTest` / `ReturnsTest` collide on a
`"9" + nanoTime % 1e9` mobile-number fixture. Neither was introduced here; both
are worth fixing.

---

## 14. Files changed

**Backend — main (8 files, 1 new migration)**

| File | Change |
|---|---|
| `payment/GatewayPaymentService.java` | Consults `PaymentCollection`; refuses merchant-collects; stamps `collectionModel` |
| `entity/Payment.java` | `collectionModel` field |
| `db/migration/V63__payments_record_who_collected.sql` | **new** — nullable column, deliberately not backfilled, VERIFY block raises if it was |
| `order/cancellation/CancellationPolicy.java` | Removed the invented 5% default; `parseCap` fails closed; added `capIsDecided()`; free cancellation while undecided |
| `store/StoreOperationsService.java` | Refuses fee terms with an explanation while no cap is decided |
| `governance/MerchantGovernance.java` | Constants → configurable instance fields (`governance.*`) |
| `platform/ShopReliability.java` | Constants → configurable instance fields (`reliability.*`) |
| `discovery/ShopOffers.java` | `MAX_SHOPS_COMPARED = 20`; bounded comparison |
| `controller/StoreAdminController.java` | Exposes `feeChargingAvailable` |

**Flutter — lib (8 files)**

`features/products/domain/product_models.dart` (nullable price + `isBuyable` /
`hasPrice` / `priceOrZero`), `shared/widgets/product_card.dart`,
`shared/widgets/variant_picker_sheet.dart`,
`features/products/presentation/product_detail_screen.dart`,
`features/wishlist/presentation/wishlist_screen.dart`,
`features/admin/presentation/admin_product_list_screen.dart`,
`admin_product_form_screen.dart`, `admin_variant_form_dialog.dart`.

**Docs**

`docs/ARCHITECTURE_REPORT.md` (§23/§24 brought in line with the verified
numbers), `docs/FINAL_HARDENING_REPORT.md` (this file).

---

## 15. Tests added

**+16 backend tests, +5 Flutter tests.**

| File | Tests | What it pins |
|---|---|---|
| `platform/TheNewSurfacesAreAlsoScopedTest.java` **(new)** | 9 | HTTP-layer IDOR and horizontal privilege escalation across ratings, dues, and governance — each with a positive control |
| `policy/NoInventedCommercialAmountTest.java` **(new)** | 6 | No money literal can reappear in `billing` or `order/cancellation`; the cap fails closed; the `REQUIRES FOUNDER DECISION` markers and the config keys still exist |
| `payment/GatewayPaymentOwnershipTest.java` | +1 | `collectionModel` is recorded as `PLATFORM_COLLECTS` after `prepareCheckout` |
| `frontend/test/features/products/domain/out_of_stock_has_no_price_test.dart` **(new)** | 5 | Parses with `sellingPrice: null`; parses with the field absent; no discount badge without a price; a priced variant keeps its price and its 25% discount; a null `inStock` keeps its price |

---

## 16. Known limitations

- **A green suite is not permission to take real money.** Blocker 1 means the
  money currently flows into one account for every shop.
- **Backend-verified is not user-verified.** Blockers 5 and 6: the marketplace
  features exist and customers cannot reach them.
- **No performance claim is made.** Blocker 7.
- **No compliance claim is made.** No statement in this report or in the code
  asserts regulatory compliance of any kind.
- **The empty-database path for V59–V63 is unexercised.** Blocker 8.
- **This environment cannot produce a shippable Android build.** Blocker 4.
- Several Part 3–4 features are deliberately absent rather than stubbed
  (blocker 9), and GP-STORE deliberately does not control merchant delivery
  operations.

---

## 17. Recommended next 5 actions

1. **Decide the payment collection model** (blocker 1) — with your provider and
   your legal advice, not in code. The seam is built and currently refuses to
   proceed for any shop marked as collecting directly, so nothing silently
   misroutes money while you decide.
2. **Wire the Flutter app to the marketplace APIs that already exist** (blocker
   5) — preferred shops, discovery, the compare/25% surface, the
   progressive-radius message, and per-shop cart totals from
   `/api/carts/mine/by-shop`. This is the highest-value ordinary work left, and
   it needs no decision from you.
3. **Run the live two-shop journey through the app** against a
   `MULTI_SHOP_PRODUCTION` backend (blocker 6), ideally straight after (2), and
   on a machine that can build an APK (blocker 4).
4. **Set the numbers you want set** (blockers 2 and 3) — the cancellation cap,
   any commercial amounts, and the six governance/reliability thresholds. Every
   one is a property; none needs a release. Until then the system charges
   nobody, which is the correct failure direction but not a business model.
5. **Exercise a fresh-database bootstrap and a first load test** (blockers 8 and
   7) — an empty database through V1–V63 under `update` then `validate`, and a
   real concurrency measurement so that any capacity figure you ever quote is
   one somebody actually observed.
