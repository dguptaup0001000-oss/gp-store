# FINAL HARDENING REPORT

*GP-STORE, after the marketplace-integration phase. Supersedes the previous
edition of this file. Every figure below came from a command run in this
session against the committed code; where something was not run, or could not
be, this report says which.*

**Status: PRODUCTION CANDIDATE, pending real-device two-shop verification, a
payment/provider/legal decision, a release build, and a load measurement taken
on representative hardware.**

Not production-ready, and the gap is not test coverage. The blockers are in
N; none of them is a missing test.

---

## A. Backend test result

```
mvn clean verify
Tests run: 1755, Failures: 0, Errors: 0, Skipped: 1
BugInstance size is 0
BUILD SUCCESS
```

Against a real Postgres 16, not H2 — the Hibernate `@Filter` behaviour, the
check constraints and the append-only audit trigger are all things an
in-memory substitute would quietly not have.

---

## B. Flutter test result

Run on the project's own pin, **Flutter 3.35.7 / Dart 3.9.2**, not `stable`.

```
flutter analyze  ->  41 issues — 0 errors, 0 warnings, 41 info
flutter test     ->  +702: All tests passed!   (exit 0)
```

---

## C. Static analysis

Included above: `flutter analyze` reports **41 issues, every one `info`, 0
errors and 0 warnings**, on the pinned SDK. Backend static analysis is
SpotBugs, run as part of `mvn verify`: **`BugInstance size is 0`**.

---

## D. Security verification

| Suite | Tests | What it attacks |
|---|---|---|
| `TheNewSurfacesAreAlsoScopedTest` | 9 | Ratings moderation, cancellation dues and waivers, shop governance and appeals, the platform overview — by wrong identity and by forged id, each with a positive control |
| `CrossTenantApiAccessTest` | 8 | Cross-tenant access at the route |
| `CrossTenantDataIsolationTest` | 12 | Data isolation |
| `CrossTenantShopCatalogTest` | 16 | Catalogue isolation |
| `ShopScopeIsNotOptionalTest` | 7 | Enumerates every `ShopOwned` entity; fails if one gains a `shop_id` nothing enforces |
| `ShopStaffAndRidersTest` | 8 | A rider confined to the roster shop |
| `MarketplaceOversightTest` | 5 | Platform admin sees every shop; a shop owner is refused the overview |

All green in the run above.

---

## E. Fresh database verification

`scripts/verify/fresh_database.sh`, against a genuinely empty Postgres 16:

```
public schema has 0 tables
63 migrations, head = V63
65 tables · 240 indexes · 52 foreign keys · 317 check constraints
27 tables carry shop_id
orders=0 payments=0 customers=0
Phase 2 (ddl-auto=validate) starts clean
PASSED
```

Both hours-table CHECK constraints were confirmed present afterwards by direct
query, which is the thing that was missing before.

---

## F. Migration result

63 migrations applied from empty to head **V63**, none recorded unsuccessful.
The three defects that made this impossible until this phase are in L1 below,
with why the migration files themselves were not edited.

Migrations ship inside the backend image and run at startup **after** Hibernate
creates the entity tables (`FlywayAfterSchemaConfig`), which is the order this
schema requires and the reason there is no V1.

---

## G. Marketplace verification

Each item is marked **CODE COMPLETE** (written, compiles, has tests) or
**TEST VERIFIED** (a named test actually exercised it in this session).

### G1. The customer app now reaches the marketplace backend — TEST VERIFIED

The features existed and customers could not get at them. The app called three
marketplace endpoints; it now calls all of them.

| Brief item | State |
|---|---|
| Progressive-radius discovery | TEST VERIFIED — `/api/marketplace/discovery`, ladder and wording both the server's |
| Nearby shops | TEST VERIFIED |
| "No shops within X km. Showing results within Y km." | TEST VERIFIED — printed verbatim from the server, not composed in Dart |
| Search farther | TEST VERIFIED — driven by `nextRadiusKm`; absent when there is nowhere farther |
| Preferred shops | TEST VERIFIED — per category, cap enforced server-side |
| Best Deal | TEST VERIFIED |
| Final customer cost comparison | TEST VERIFIED — `finalPayable`, delivery included and shown up front |
| Change Shop | TEST VERIFIED |
| Compare Other Shops | TEST VERIFIED |
| Preferred shop priority | TEST VERIFIED — reorders, never filters |
| Access to other shops | TEST VERIFIED — every shop stays in the list |
| Shop-specific catalogue | CODE COMPLETE — `ShopSwitch` discards the previous shop's cached catalogue |
| Shop-specific price and stock | TEST VERIFIED |
| Out-of-stock stays visible, has NO price, cannot be added | TEST VERIFIED |
| Multi-shop basket, separated, per-shop subtotal / delivery / discount / final payable | TEST VERIFIED — `/api/carts/mine/by-shop` |
| Never merged into one order | TEST VERIFIED (app) + TEST VERIFIED (backend) |
| Separate payment per shop | TEST VERIFIED — see G3 |
| Order history separated by shop | TEST VERIFIED |
| Shop profile: rating, recent rating, verified count, verification status, hours, delivery, policies, about, reliability | TEST VERIFIED |
| No internal governance fields exposed to customers | TEST VERIFIED — the customer app calls no governance, reliability or payment-collection route |

`Storefront` was parsing 6 of the 15 fields the server sends, which is why a
customer could not see that a shop was shut, paused, unverified or out of
range. It parses all of them now.

**Nothing was moved into Dart that the server already decides** — not
distance, not ranking, not the 25% price-gap verdict, not the preference cap,
not a shop's delivery charge. The 5-second cancellation countdown and the
single-shop screens are untouched.

### G2. A cancelled, refunded order could be brought back to life — TEST VERIFIED

`OrderLifecycle` has held the transition table for some time and `OrderService`
consulted it properly. Three other services wrote `order.setOrderStatus(...)`
directly, each with its own hand-written guard or none:

- marking a **delivery** DELIVERED forced its order to DELIVERED **even when
  the order had already been cancelled and refunded** — the customer's money
  returned, the order showing as delivered;
- the stale-payment sweep cancelled anything not already CANCELLED or
  DELIVERED, which includes **COMPLETED and REJECTED** — both terminal,
  neither cancellable.

All four write paths now go through `OrderStatusChange`, which delegates every
decision to the table and adds only the refusal and the no-op. Re-asserting a
state stays a no-op, so duplicate webhooks and retried requests do not become
failures. Ten named tests (`OneDoorForOrderStatusTest`).

Routing them through the table immediately failed four worker tests, which was
the point: the delivery flow could take an order from CONFIRMED straight to
OUT_FOR_DELIVERY, a move the order's table has always refused. The cause was
that `DeliveryStatus.PACKED` — "deliberately the same word as
`OrderStatus.PACKED`, because it is the same event", per its own comment — was
only ever mirrored onto the order by the QR-scan path. A shop working the
delivery screen left the order at CONFIRMED, so **the customer never saw their
order packed**, and it could be delivered while its status still said the shop
had merely accepted it. Both sides now record the same event.

### G3. A two-shop checkout paid one shop — TEST VERIFIED

A basket from two kiranas becomes two orders with two payment rows owed to two
merchants; that is what the server has always done. The checkout screen paid
`orderResult.orderId` — the **first** shop's — and then showed a confirmation
reading "2 orders placed". The customer paid one shop, believed they had paid
both, and the second order sat unpaid until the sweep cancelled it.

Checkout now charges each shop order in turn, **still as separate payments and
never as one combined charge**, names the shop it is opening, and on failure
sends the customer to the order that is actually unpaid rather than to the
first one. Six named tests (`every_shop_gets_paid_test.dart`).

### G4. No new environment could be provisioned at all — TEST VERIFIED

The previous report listed the fresh-database bootstrap as *unverified*. It
was run, and failed — three times, each for the same underlying reason. This
was not a gap in the evidence; it was a gap in the product. Until this phase,
GP-STORE could not be stood up on an empty database: not a staging box, not a
second region, not a restore.

There is no V1 (`db/migration/README.md`): Hibernate creates the entity tables
and Flyway applies V2 onward on top. On an existing database that works. On an
empty one Hibernate creates every table at the entity's **current** shape, and
the historical migrations then run against it as though it were at its shape
of the day.

| Migration | What happened |
|---|---|
| V33 | Inserts the starting settings row naming only the columns of its day. `cancellation_charges_delivery` and `free_cancellation_seconds` were added later as NOT NULL DEFAULT, but Hibernate had created them NOT NULL **without** the default, so the insert wrote nulls and the bootstrap died. |
| V46 | The same, with the founding shop: `shops.verification_level`, NOT NULL, created without the default V55 gives it. |
| V55 | Declares `ck_shop_hours_day` and `ck_shop_hours_order` inside a `CREATE TABLE IF NOT EXISTS` that Hibernate had already satisfied. Both constraints were therefore **silently absent on every new environment**. V55's own VERIFY block caught it, which is an argument for writing them. |

The migrations were **not** edited: Flyway checksums applied scripts, so
changing one would stop the next production deploy booting — trading a failure
nobody has hit for one everybody would. Instead the entities declare the same
database defaults their migrations declare, and the two hours tables joined the
bootstrap-only reset list. **Existing databases are untouched**: those columns
are already there with exactly these definitions, and `validate` compares type
and nullability rather than defaults.

### G5. A fixture that could not do what it claimed — TEST VERIFIED

Twenty-three test classes built a mobile number as
`"9" + String.valueOf(System.nanoTime()).substring(0, 9)`. That reads as "nine
plus nanosecond digits" and is not: the first nine characters are the
**high-order** digits of the clock, which change about once every hundred
milliseconds. Any two customers created inside the same tenth of a second got
the same number, and `customers.mobile_number` is unique. It was carried in the
previous report as a low-priority flake; it is a near-certainty whenever two
fixtures land close together, and it failed a full build in this pass.
Replaced with `TestMobileNumbers.unique()`, which cannot collide within a JVM
and is very unlikely to across forks.

### G6. Smaller, and each one real

- **`storeStatusProvider` disposal race** — it registered its disposal
  bookkeeping inside an `async*` generator, so a provider created and
  invalidated in the same frame (exactly what `ShopSwitch` does when a customer
  changes shop as a screen mounts) reached `ref.onDispose` after disposal and
  threw. Registration is now part of provider creation.
- **Preferred-first ordering** now watches the preferences it is ordered by, so
  starring a shop no longer leaves the list claiming distance order.
- **The Flutter SDK pin** moved out of three CI YAML files into `pubspec.yaml`
  and `pubspec.worker.yaml`. A developer running plain `flutter upgrade` now
  finds out from `pub get` instead of from `build_runner` failing with
  `Missing implementation of visitDotShorthandPropertyAccess` — an error that
  looks like broken code and is not.
- **Bounded shop comparison** stays at 20 shops.

---

## H. Two-shop verification

14 widget tests (`two_shop_journey_test.dart`) tapping through the **shipped**
`ShopPickerScreen`, `ShopProfileScreen`, `CompareShopsSheet`,
`ProductDetailScreen`, `CartScreen` and `OrderHistoryScreen`. Only the HTTP
adapter is overridden — not the repository, not the providers, not the models,
because overriding those tests the fixture instead of the app.

The two shops are deliberately unlike each other. Shop A: 1.2 km, trusted, dal
at ₹100, ₹20 delivery. Shop B: 4.6 km, not trusted, **out of the dal so it has
no price for it**, ₹45 delivery. Shop B wins the comparison at ₹105 against
₹120 — the opposite of what comparing shelf prices alone would say.

Against the brief's 25 steps:

| # | Step | Result |
|---|---|---|
| 1–2 | Marketplace opens, A and B discoverable | **UI verified** |
| 3 | Customer selects Shop A | **UI verified** — a tile opens the profile; "Shop here" is a deliberate act |
| 4–6 | Views products, selects one, adds it | **UI verified** at the product screen; the browse grid itself was not driven |
| 7–8 | Compares and finds Shop B | **UI verified** |
| 9 | Adds Shop B's product | **UI verified** via the basket state |
| 10 | Basket visibly separates A and B | **UI verified**, with each shop's own money |
| 11–14 | Checkout and payment per shop | **NOT driven through the UI.** The rule that every shop is charged is unit-tested (`ordersToPay`); the gateway SDK cannot be exercised in a widget test |
| 15–16 | Two orders, two receipts | **UI verified** for the two orders in history; the invoice screen was not driven |
| 17–18 | Shop A cannot see Shop B's order | **Backend verified only** — merchant isolation is not a customer-app screen |
| 19 | Customer sees both orders | **UI verified** |
| 20 | Cancellation/refund is shop-specific | **Backend verified only** |
| 21–22 | Out-of-stock cannot be bought and has no price | **UI verified** — no `₹` renders at all |
| 23 | Preferred shop behaviour | **UI verified** — starring Shop B stores it and the list re-orders |
| 24 | Best Deal | **UI verified** |
| 25 | Progressive radius | **UI verified** — including the server's widened sentence |

**What this does not prove**, stated plainly: this is the app against a
contract, not the server honouring it, and it is not the app on a phone against
a live `MULTI_SHOP_PRODUCTION` deployment. That last one cannot be done here —
see D.

---

## I. Load test result

Run with the repository's own k6 harness (`load-tests/`), against this
build booted locally on a **4 vCPU / 16 GB** container. A new script,
`marketplace-capacity.js`, covers the read path the old one did not: shop
discovery, the storefront, and price comparison. It follows
`staged-capacity.js`'s conventions and **refuses to start** if no shop serves
the pin it is given, because discovery against an empty point answers in a
millisecond and would look like the fastest stage ever recorded.

**Marketplace path** — `/api/marketplace/discovery`, `/api/marketplace/shops`,
`/api/marketplace/shops/{id}`:

| VUs | discovery p95 | discovery p99 | shops-near p95 | storefront p95 | 502 | unexpected 503 | network errors |
|---|---|---|---|---|---|---|---|
| 25 | 203 ms | 218 ms | 32 ms | 24 ms | 0 | 0 | 0 |
| 50 | 168 ms | 180 ms | 14 ms | 16 ms | 0 | 0 | 0 |
| 100 | 228 ms | 251 ms | 10 ms | 9 ms | 0 | 0 | 0 |
| 250 | 284 ms | 317 ms | 10 ms | 8 ms | 0 | 0 | 0 |
| 500 | 460 ms | 538 ms | 9 ms | 8 ms | 0 | 0 | 0 |
| 1000 | 1.12 s | 1.27 s | 14 ms | 10 ms | 0 | 0 | 0 |

At 1000 VUs over 30s: **12,146 requests, 0 failed (0.00%), 300.2 req/s**, JVM
resident 991 MB, peak Postgres connections 26. Every threshold passed at every
level (p95 < 2s, p99 < 4s, no 502, no unexpected 503, no network error).

**Browse path** — the pre-existing `staged-capacity.js`, unchanged:

| VUs | categories p95 | feed p95 | requests | failed | throughput |
|---|---|---|---|---|---|
| 250 | 168 ms | 5 ms | 2,070 | 0.00% | 70 req/s |
| 1000 | 301 ms | 2 ms | 8,201 | 0.00% | 278 req/s |

**What these numbers are and are not.** Discovery is the expensive read and
the only one that grows with load — it does a per-shop radius test and reads
each shop's open/closed state in that shop's own scope. Everything else stays
in single-digit milliseconds. **The load generator ran on the same four cores
as the server**, so these are a floor, not a capacity.

**No concurrent-user figure is claimed.** A k6 VU is not a user: it is a
thinking shopper with 1.5–4 s pauses, so 1000 VUs produced 300 req/s, not
1000. Quoting "1000 concurrent users supported" from this would be exactly the
invention the brief forbids — and it was measured on a container, not on the
Hostinger VPS the shop actually runs on.

---

## J. Android build result

**ENVIRONMENT BLOCKED. No APK exists and none is claimed.**

The real command was attempted, exactly as CI runs it:

```
flutter build apk --release --split-per-abi --target-platform android-arm,android-arm64 \
  --flavor customer -t lib/customer_main.dart \
  --dart-define=GPSTORE_APP=customer \
  --dart-define=APP_ENV=production \
  --dart-define=API_BASE_URL=https://api.gpstore.co.in/v1
```

```
[!] No Android SDK found. Try setting the ANDROID_HOME environment variable.
$ ls build/app/outputs/flutter-apk/
ls: cannot access 'build/app/outputs/flutter-apk/': No such file or directory
```

**Android artifact build blocked by environment: the Android SDK is
unavailable because `dl.google.com` is inaccessible through this environment's
proxy.** No application code was changed to work around it. Flutter's own
engine artifacts download fine from `storage.googleapis.com`; only
`dl.google.com` is refused.

What *was* verified without the SDK: `verify_apk_release.py --self-test`
passes, so the release-verification logic is sound; `assert_app_separation.py`
and `assert_worker_location_manifest.py` both pass.

---

## K. Deployment result

**CREDENTIAL AND ENVIRONMENT BLOCKED. Nothing was deployed in this session,
and production was not contacted.**

Production is the existing Hostinger VPS — Docker Compose + Traefik, per
`backend/HOSTINGER_DEPLOYMENT.md`. No provider was changed, added, or
suggested.

Why it could not be done from here:

1. **The repo deploys on merge to `main`.** `.github/workflows/deploy-production.yml`
   triggers on `push: [main]` and holds `PROD_HOST`, `PROD_USER` and
   `PROD_SSH_PRIVATE_KEY` as GitHub Actions secrets. This work is on
   `claude/progress-remaining-work-vjo7fx`. **Merging to `main` deploys to the
   live shop and is a decision for you, not something to do unasked.**
2. **No SSH key exists in this container** (`~/.ssh` is empty), so a manual
   deploy was not possible either.
3. **`api.gpstore.co.in` is blocked by this environment's proxy** (D3), so even
   a read-only health check could not be run.

Configuration reviewed (read-only) and found correct: secrets come from
`backend/.env` **on the VPS**, never from source; no `.env`, `key.properties`
or `google-services.json` is tracked in git; the Flutter production URL is
`https://api.gpstore.co.in/v1`; Flyway migrations ship inside the image and run
at startup after Hibernate (`FlywayAfterSchemaConfig`).

**Payment on the deployed stack is unchanged and must stay that way** — see F1.
Nothing in this phase enables real-money collection.

---

## L. Known limitations

### L1. What made provisioning impossible until this phase

The previous report listed the fresh-database bootstrap as *unverified*. It
was run, and failed — three times, each for the same underlying reason. This
was not a gap in the evidence; it was a gap in the product. Until this phase,
GP-STORE could not be stood up on an empty database: not a staging box, not a
second region, not a restore.

There is no V1 (`db/migration/README.md`): Hibernate creates the entity tables
and Flyway applies V2 onward on top. On an existing database that works. On an
empty one Hibernate creates every table at the entity's **current** shape, and
the historical migrations then run against it as though it were at its shape
of the day.

| Migration | What happened |
|---|---|
| V33 | Inserts the starting settings row naming only the columns of its day. `cancellation_charges_delivery` and `free_cancellation_seconds` were added later as NOT NULL DEFAULT, but Hibernate had created them NOT NULL **without** the default, so the insert wrote nulls and the bootstrap died. |
| V46 | The same, with the founding shop: `shops.verification_level`, NOT NULL, created without the default V55 gives it. |
| V55 | Declares `ck_shop_hours_day` and `ck_shop_hours_order` inside a `CREATE TABLE IF NOT EXISTS` that Hibernate had already satisfied. Both constraints were therefore **silently absent on every new environment**. V55's own VERIFY block caught it, which is an argument for writing them. |

The migrations were **not** edited: Flyway checksums applied scripts, so
changing one would stop the next production deploy booting — trading a failure
nobody has hit for one everybody would. Instead the entities declare the same
database defaults their migrations declare, and the two hours tables joined the
bootstrap-only reset list. **Existing databases are untouched**: those columns
are already there with exactly these definitions, and `validate` compares type
and nullability rather than defaults.

### L2. Other verification run in this session

A test that passes whether or not the protection exists is not a test.

| Protection removed | Test that failed, by name |
|---|---|
| The server's widened sentence in the picker | `Finding the shops nothing nearby offers the next rung, and says what it found` |
| The per-shop money block in the basket | `One basket, two shops the basket separates the shops and prices each one itself` |
| `@Filter` on `CustomerCancellationDue` (earlier pass) | `TheNewSurfacesAreAlsoScopedTest$Dues.theOutstandingListIsScoped` |

All restored.

Not rewritten, because they already existed and duplicating them would be
worse than useless: `CashfreeSignatureVerifierTest` (a bad signature is
rejected), `CashfreeWebhookDedupTest` (a duplicate webhook makes no second
state change; concurrent duplicates apply once), `GatewayPaymentStateTest`
(underpayment does not confirm; a late success webhook cannot revive a
cancelled order; a superseded attempt does not confirm the current payment),
`GatewayPaymentOwnershipTest`, `GatewayLockOrderingTest`,
`PaymentWebhookControllerTest`.

**The client's opinion cannot enter.** `POST /api/payments/order/{id}/verify`
takes no request body at all — it asks Cashfree and applies the answer. There
is no field anywhere in which a client could assert `payment_success=true`.

Verified without building: `assert_app_separation.py` (customer and admin Dart
graphs are separate) and `assert_worker_location_manifest.py` both pass, and
`verify_apk_release.py --self-test` passes — so the release-verification logic
itself is exercised even though no APK exists to run it against.

Configuration reviewed and correct for the pinned toolchain: AGP 8.11.1,
Kotlin 2.2.20, Gradle 8.14, google-services 4.4.2, Crashlytics 3.0.2;
`compileSdk`/`minSdk`/`targetSdk`/`ndkVersion` all delegate to the Flutter
toolchain so they follow the pin; release signing reads `key.properties` with
v1+v2+v3 enabled and the build **fails hard** if release signing is
unconfigured (debug signing only behind an explicit `ALLOW_DEBUG_RELEASE_SIGNING=1`);
R8 `minifyEnabled` + `shrinkResources` + project ProGuard rules; `jniLibs`
uncompressed and page-aligned; Crashlytics mapping upload wired.

### L3. Not tested

Stated as gaps, not as passes.

1. **The app on a real device against a live backend.** Section H is the widget layer.
   Nobody has installed GP-STORE on a phone and bought from two kiranas. This is
   the single most valuable thing still outstanding and it is blocked by D1.
2. **Checkout and payment through the UI** (journey steps 11–14). The rule is
   unit-tested; the gateway is not exercisable in a widget test.
3. **Merchant-side isolation through a merchant screen** (steps 17–18). Proven
   at the backend; the merchant app was not driven.
4. **The invoice/receipt screen** (step 16, second half).
5. **The browse grid and search screens** — the product detail screen was driven,
   the grid that leads to it was not.
6. **Return, pickup and refund through the UI.**

### L4. Environment limits

Code is correct; this machine cannot exercise it. **No application code was
changed to hide any of these.**

**D1. No Android artifact can be built here.** Re-confirmed in this session:

```
$ curl https://dl.google.com/android/repository/repository2-3.xml
curl: (56) CONNECT tunnel failed, response 403

$ flutter build apk --release
[!] No Android SDK found. Try setting the ANDROID_HOME environment variable.
```

Note the shape: Flutter's **own** engine artifacts download fine from
`storage.googleapis.com` — six `Downloading android-*` lines succeeded in under
a second each. Only `dl.google.com`, where the Android SDK lives, is refused.
**Therefore no APK and no AAB exists, and none is claimed.** The Gradle
configuration was reviewed (B8) and not touched, because nothing is wrong with
it.

**D2. D1 blocks the device test.** The live two-shop journey needs an
installable app.

**D3. Production is unreachable from this environment.** This agent's HTTPS
proxy refuses `api.gpstore.co.in:443` with the same 403 it gives
`dl.google.com` — confirmed in the proxy's own failure log:

```
{"kind":"connect_rejected",
 "detail":"gateway answered 403 to CONNECT (policy denial or upstream failure)",
 "host":"api.gpstore.co.in:443"}
```

So **no health check against live production was performed**, and none is
claimed. There are also no SSH keys in this container (`~/.ssh` is empty), so
no deployment could be driven from here either.

**D5. The load numbers were taken on a shared 4-core container**, with the
generator on the same cores as the server. They are a floor, and the VPS is a
different machine.

**D4. `OpsStatusServiceTest.diskOnARealDirectoryIsHealthy`** fails whenever the
2.4 GB Flutter SDK is resident — it pushes free disk under the 10% floor the
test asserts. The backend and Flutter suites cannot be verified in one
invocation on this box; they were run separately.

---

## M. Founder / business decisions still required

### Founder

Nothing below was invented, defaulted, or guessed at.

**E1. Commercial amounts — still none exist.** No cancellation percentage, no
commission, no weekly fee, no governance monetary threshold, no fee cap.
`CancellationPolicy.capIsDecided()` is false, **every cancellation is free**,
and a merchant who tries to set fee terms is refused with an explanation.
`NoInventedCommercialAmountTest` fails the build if a money literal reappears
in `billing` or `order/cancellation`.

**E2. Governance and reliability thresholds.** 90-day warning decay, 180-day
final warning, and TRUSTED at 25 orders / 92% completion / 8% returns are
defensible working defaults, not decisions you made. All six are configuration
(`governance.*`, `reliability.*`), none is a `public static final`, and the
`REQUIRES FOUNDER DECISION` markers are asserted by test.

**E3. The order-status vocabulary.** The brief names CREATED, PREPARING,
READY_FOR_DELIVERY, FAILED and EXCEPTION. This codebase has
PENDING_CONFIRMATION, PACKING, READY_TO_DISPATCH and DELIVERY_FAILED for the
first four — same concepts, different words, persisted across 63 migrations
and live orders. **Renaming them was not done**, because it would rewrite an
enum production rows hold for a vocabulary difference. REFUNDED and RETURNED
are deliberately *not* order statuses: refunds and returns are their own rows,
which is what lets one order carry several partial refunds — a status column
cannot express that. **EXCEPTION has no equivalent and none was invented.** If
you want the brief's names, that is a migration and a decision; say so.

**E4. Merchant-arranged return pickup.** Not built, and the reason is a
business rule rather than a gap: **GP-STORE does not control merchant delivery
operations**. A GP-STORE-run pickup workflow would contradict that. Today a
shop states its own return terms (shown on its profile) and gives its own
phone number. Whether GP-STORE should have a pickup concept at all is yours.

**E5. Escalation and intervention.** `LedgerEntryType.INTERVENTION_RECOVERY`
exists and nothing issues one, because "where policy permits" names a policy
that does not exist: when may a customer escalate, on what evidence, how much
may GP-STORE compensate, and how much is recovered from the merchant. Every
one of those is a number or a rule you have not set. **A normal refund does not
become a merchant violation** — nothing on the refund path touches governance,
and `ARefundIsNotAnAccusationTest` fails the build if that changes.

### Business, legal and provider

**F1. The payment collection model. CRITICAL.** One Cashfree account carries
every shop's money, which makes GP-STORE a payment aggregator in substance.
The seam is real and refuses rather than lies: `prepareCheckout` consults
`PaymentCollection` and **throws** for any shop configured to collect its own
payments rather than quietly taking that money into the platform account.
`payments.collection_model` records who collected, from V63 onward, and is
deliberately **not backfilled** — nobody knows how the historical ones were
collected and inventing an answer would corrupt the only record of it.

**No provider architecture was invented. No merchant account IDs, real or
fake, are in production logic. No settlement schedule, no KYC assumption, and
no regulatory compliance is claimed anywhere in this codebase or this report.**

---

## N. Exact remaining blockers

Numbered, classified, most serious first.

| # | Blocker | Class |
|---|---|---|
| 1 | **Payment collection model undecided.** One account holds every merchant's money. | BUSINESS/LEGAL DECISION REQUIRED |
| 2 | **The app has never run on a real device against a live marketplace backend.** Widget-level only. | ENVIRONMENT BLOCKED (by 3) + NOT TESTED |
| 3 | **No APK or AAB can be built here** (`dl.google.com` 403). | ENVIRONMENT BLOCKED |
| 4 | **Load is measured, but on a 4-core container with the generator on the same cores — not on the Hostinger VPS.** No capacity figure for the real host. | MEASURED, NOT ON TARGET HARDWARE |
| 5 | **No commercial amounts exist**, so cancellation is free for everyone and merchants cannot set fee terms. | FOUNDER DECISION REQUIRED |
| 6 | **Governance and reliability thresholds are unapproved defaults.** | FOUNDER DECISION REQUIRED |
| 7 | **Checkout payment is not driven end to end through the UI**; the per-shop charging rule is unit-tested only. | NOT TESTED |
| 8 | **Escalation, intervention recovery and merchant pickup are not built** — deliberately not stubbed. | FOUNDER DECISION REQUIRED |
| 9 | **A merchant is not notified of a governance action**; there is no merchant push or email channel, so a shopkeeper finds out by looking. | CODE COMPLETE elsewhere, LOW |
| 10 | **Thirty-five tables are created by both Hibernate and a migration.** Fixed where it broke the bootstrap (A4), but on any fresh database the migration's `CREATE TABLE` is still a no-op for the other 32, so constraints declared inside those statements may be absent in new environments and present in production. | MEDIUM — needs an audit, not a guess |

### Recommended next actions

1. **Decide the payment collection model** (G1) with your provider and your
   legal advice. Nothing else on this list changes what it is worth.
2. **Build and install the app, then run the two-shop journey on a phone**
   (G2, G3) on a machine with Android SDK access. Everything the widget tests
   assert should hold; what they cannot see is the gateway, the real network,
   and the actual shops.
3. **Re-run the load harness on the VPS itself** (G4):
   `BASE_URL=https://api.gpstore.co.in/v1 VUS=50 HOLD_TIME=30s k6 run
   load-tests/marketplace-capacity.js` — start low, it is the live shop. The
   container numbers in B9 are a floor, not the VPS's capacity.
4. **Set the numbers** (G5, G6). Every one is a property; none needs a release.
   Until then the platform charges nobody, which is the correct failure
   direction and not a business model.
5. **Audit the other 32 dual-created tables** (G10) — compare each migration's
   `CREATE TABLE` against the schema a fresh bootstrap produces, and add to the
   bootstrap reset list anything that carries a constraint Hibernate does not.

---

## O. Production readiness score

**66 / 100.**

Two points up from the previous edition: the load path is now measured rather
than unknown, and a fresh database provisions. Nothing else moved, because
nothing else was a test-coverage problem.

Not a test-count score. What it is made of:

| | |
|---|---|
| Tenancy, isolation and authorization | strong — attacked at the HTTP layer, with positive controls, and mutation-checked |
| Order lifecycle correctness | much stronger than at the start of this phase; a resurrection bug and a cancellation bug are gone |
| Customer-facing marketplace | functional for the first time; verified at the widget layer, never on a device |
| Payment safety | structurally sound and honest about what it does not know; **the model itself is undecided**, which caps this score on its own |
| Provisioning | went from *impossible* to *verified* in this phase |
| Performance | measured on a 4-core container — 1000 VUs, 0 errors, 300 req/s, discovery p95 1.12 s — but not on the VPS |
| Commercial model | deliberately absent |
| Deployment of this code | **not done** — it is on a branch, and production is still running the previous build |

Roughly: the engineering is in good shape and the business decisions are not
made. Points are withheld for the payment decision (a decision, not a defect),
for never having run where customers will run it, for load measured on the
wrong hardware, for one unaudited class of schema drift, and for this code not
yet being deployed. **The score would not move by adding tests.**

**Do not read a green suite as permission to take real money through this.**

---

## P. How to test this from an Android phone

**Nothing below has been done. It is the procedure, not a result.** It becomes
possible once J (a build) and K (a deploy of this code) are unblocked.

### P0. Prerequisites, in order

1. **Merge to `main`.** `.github/workflows/deploy-production.yml` fires on push
   to `main` and deploys to the Hostinger VPS. Until then production is running
   the previous code, not this. **This is your call: it is the live shop.**
2. **Build the APK on a machine with the Android SDK:**

```bash
cd frontend
flutter build apk --release --split-per-abi --target-platform android-arm,android-arm64 \
  --flavor customer -t lib/customer_main.dart \
  --dart-define=GPSTORE_APP=customer \
  --dart-define=APP_ENV=production \
  --dart-define=API_BASE_URL=https://api.gpstore.co.in/v1
# → build/app/outputs/flutter-apk/app-arm64-v8a-customer-release.apk
```

   `APP_ENV=production` is not optional: a release build without it **throws at
   startup** rather than shipping pointed at a development host
   (`AppEnvironment.assertReleaseBuildIsConfigured`).

3. **Confirm the backend is the one you just deployed** before installing
   anything:

```bash
curl https://api.gpstore.co.in/v1/api/health        # → GP-STORE Backend Running Successfully!
curl https://api.gpstore.co.in/v1/api/health/ready  # → {"status":"ready"}
curl "https://api.gpstore.co.in/v1/api/marketplace/mode"
curl "https://api.gpstore.co.in/v1/api/marketplace/discovery?lat=<your lat>&lng=<your lng>"
```

   If `mode` says `SINGLE_SHOP`, the app will not draw a shop switcher at all
   and nothing below applies — that is the platform mode setting, not a bug.

4. **Two shops must exist and both must reach your address.** Discovery
   returning `[]` is a correct answer to "nobody delivers to you", and it will
   look like a broken app. Check with the `discovery` call above first.

### P1. Customer

| # | Step | What proves it worked |
|---|---|---|
| 1 | Log in | Home screen loads |
| 2 | Discover shops | Both shops listed, nearest first, each with its distance and GP-STORE's verification wording |
| 3 | Select Shop A | Tapping a tile opens its **profile**, not a silent switch. "Shop here" is the deliberate act |
| 4 | View Shop A | Rating, last-30-days rating, verified-order count, open/closed, delivery radius, the shop's own return terms |
| 5 | Browse products | Shop A's prices, Shop A's stock |
| 6 | Out-of-stock item | Card still on the shelf, **no ₹ figure anywhere on it**, Add disabled |
| 7 | Select Shop B | Same, from the picker |
| 8 | Compare A/B | "Compare other shops" on a product; both shops with **final payable** |
| 9 | Final payable | Each row shows item + discount + delivery. The cheaper **total** wins, not the cheaper shelf price |
| 10 | Preferred shop | Star a shop under "My shops"; it moves to the top. A third in the same category is refused by the server |
| 11–12 | Add from both shops | Two shops in one basket |
| 13 | Basket separated | Two named sections, each with its own subtotal, delivery and total, and "paid separately" said **before** payment |
| 14–15 | Checkout | **You should be sent to the gateway twice, once per shop, labelled "shop 1 of 2" / "shop 2 of 2".** One gateway visit for a two-shop basket is the bug fixed in G3 reappearing — report it |
| 16 | Separate orders | Confirmation says "2 orders placed" and lists both |
| 17 | Order history | Two rows, two shop names, two statuses |
| 18 | Cancellation | The 5-second countdown still appears. **Cancelling is free** — any fee would mean a commercial number got invented (M) |
| 19 | Status changes | Ask the merchant to advance one order; the customer view follows, including **Packed** |

### P2. Merchant

Log in as Shop A's owner. **Only Shop A's data may appear anywhere**: products,
stock, prices, orders, hours, accepting-orders switch, workers, governance
record, ratings, refunds. If Shop B's name or an order of Shop B's is visible on
any screen, stop and report it — the backend refuses this (D) and the app
should never be in a position to ask.

Try to set a cancellation fee. It must be **refused with an explanation**, not
accepted: no platform cap exists yet (M).

### P3. Worker

Log in as one of Shop A's riders. Check: activation/status, only your own
assignments, pack scan, then **Packed → Ready → Out for delivery → Delivered**
in order. Skipping a step must be refused. You must not see an order belonging
to another merchant.

### P4. Platform admin

Merchant management, shop governance, verification, appeals, the cross-shop
overview. A platform admin **should** see every shop — that is the difference
being tested. Customer passwords, tokens and payment credentials must not
appear anywhere.

### P5. Security — do this deliberately

With a merchant's session, change the id in the URL and re-issue the request:

```
GET  /v1/api/orders/{another shop's order id}
GET  /v1/api/shop-ratings/manage          with X-Shop-Id: <another shop>
POST /v1/api/cancellation-dues/{someone else's due}/waive
GET  /v1/api/shop/governance              with X-Shop-Id: <another shop>
GET  /v1/api/platform/governance/actions  as a shop owner
```

**Every one must fail with 403 or 404.** A 200 with somebody else's data is a
release-stopping defect. These are the exact routes the nine tests in D attack;
this is the same attack by hand, against a real deployment.
