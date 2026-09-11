# FINAL HARDENING REPORT

*GP-STORE, after the marketplace-integration phase. Every figure here came
from a command run in this session against the committed code. Where something
was not run, or could not be, this report says so and says why — it does not
report it as passed.*

**Status: NOT PRODUCTION-READY, and not deployed.**

This code is on a branch. Production is still running the previous build. No
APK exists. Nothing is deployed — though a staging stack for the VPS you
already own is now written and validated, so that is a decision rather than a
gap. Money still lands in one platform account, contradicting Decision W1 —
which already says a merchant's product proceeds are the merchant's — so that
one is a known gap against a recorded decision, not an open question. None of
that is a test-coverage problem and none of it is fixed by more tests.

Results are separated by **how** they were verified, because "the suite is
green" and "a customer bought something" are different claims:

| | |
|---|---|
| **A** | Verified by automated tests |
| **B** | Verified against a deployed backend — 38 real-HTTP checks, locally; **not the VPS** |
| **C** | Verified on a real Android device — **nothing; no APK, no device** |
| **D** | Verified by load test |
| **E** | Not verified |
| **F** | Blockers |
| **G** | Production risks |

---

## A. Verified by automated tests

### A1. Backend

```
mvn clean verify
Tests run: 1761, Failures: 0, Errors: 0, Skipped: 1
BugInstance size is 0   (SpotBugs)
BUILD SUCCESS
```

Against a real Postgres 16, not H2 — the Hibernate `@Filter` behaviour, the
check constraints and the append-only audit trigger are all things an
in-memory substitute would quietly not have.

### A2. Flutter

On the project's own pin, **Flutter 3.35.7 / Dart 3.9.2**:

```
flutter analyze  ->  41 issues — 0 errors, 0 warnings, 41 info
flutter test     ->  +702: All tests passed!   (exit 0)
```

### A3. Security — tenant, IDOR, horizontal privilege escalation

| Suite | Tests | What it attacks |
|---|---|---|
| `TheNewSurfacesAreAlsoScopedTest` | 9 | Ratings moderation, cancellation dues and waivers, shop governance and appeals, the platform overview — by wrong identity **and** by forged id, each with a positive control |
| `CrossTenantApiAccessTest` | 8 | Cross-tenant access at the route |
| `CrossTenantDataIsolationTest` | 12 | Data isolation |
| `CrossTenantShopCatalogTest` | 16 | Catalogue isolation |
| `ShopScopeIsNotOptionalTest` | 7 | Enumerates every `ShopOwned` entity; fails if one gains a `shop_id` that nothing enforces |
| `ShopStaffAndRidersTest` | 8 | A rider confined to the roster shop |
| `MarketplaceOversightTest` | 5 | Platform admin sees every shop; a shop owner is refused the overview |

This is the closest thing to the attack matrix in your objective 6 that can be
run without a deployment. **Merchant A reading, modifying or listing Shop B's
products, orders, workers or finances is refused, by name, in the suite above.**
What has *not* happened is the same attack by hand against a running server;
that is in E.

### A4. Database and migrations

`scripts/verify/fresh_database.sh`, against a genuinely empty Postgres 16 —
a separate database (`gpstore_freshcheck`), never the production one, and the
script refuses to run against anything named like production:

```
public schema has 0 tables
64 migrations, head = V64
65 tables · 240 indexes · 52 foreign keys · 327 check constraints
27 tables carry shop_id
orders=0 payments=0 customers=0
Phase 2 (ddl-auto=validate) starts clean
PASSED
```

Both hours-table CHECK constraints confirmed present afterwards by direct
query — the thing that was silently missing before this phase.

### A5. The two-shop journey, through the real widgets

14 tests tapping through the shipped `ShopPickerScreen`, `ShopProfileScreen`,
`CompareShopsSheet`, `ProductDetailScreen`, `CartScreen` and
`OrderHistoryScreen`. Only the HTTP adapter is overridden — not the repository,
not the providers, not the models, because overriding those tests the fixture
instead of the app.

The shops are deliberately unlike each other. Shop A: 1.2 km, trusted, dal at
₹100, ₹20 delivery. Shop B: 4.6 km, not trusted, **out of the dal so it has no
price for it**, ₹45 delivery. Shop B wins the comparison at ₹105 against ₹120 —
the opposite of what comparing shelf prices alone would say.

Covered: both shops discoverable; the picker's widened sentence; a tile opens
the profile rather than switching silently; the profile's rating, recent
rating, verified count, trusted badge, hours, delivery and policies; Shop A's
price and an enabled Add; **Shop B's out-of-stock item visible with no `₹`
anywhere and Add dead**; comparison on final payable; switching shops; starring
a preferred shop and the list re-ordering; the basket separated with each
shop's own subtotal, delivery and total; "paid separately" said before payment;
a single-shop basket drawn exactly as it always was; two orders in history with
their own shops, statuses and totals.

### A6. Mutation checks

A test that passes whether or not the protection exists is not a test.

| Protection removed | Test that failed, by name |
|---|---|
| The server's widened sentence in the picker | `nothing nearby offers the next rung, and says what it found` |
| The per-shop money block in the basket | `the basket separates the shops and prices each one itself` |
| The delivery-fee subtraction from the commissionable base | `aSingleOrderExcludesDelivery` |
| `@Filter` on `CustomerCancellationDue` | `TheNewSurfacesAreAlsoScopedTest$Dues.theOutstandingListIsScoped` |

All restored.

### A7. Business rules re-confirmed unchanged

Every rule you listed under objective 9 was checked against the code, not
assumed. Nothing was redesigned.

| Rule | Where it holds |
|---|---|
| Merchant controls delivery entirely; GP-STORE runs no central dispatch | `TerritoryDispatchService` is per-shop; no cross-merchant reassignment exists |
| One worker may deliver many orders in one trip | Delivery batches, unchanged |
| Merchant controls delivery timing and charges | Per-shop delivery pricing and schedule, unchanged |
| **GP-STORE commission on the delivery charge is 0%** | `MerchantSales.commissionableForOrder` = `total_amount − delivery_fee`. **This was unpinned** — no test mentioned it — so `DeliveryIsNotGpStoresToTaxTest` now asserts it in both the single-order and weekly paths, and is mutation-checked above |
| Separate order, payment and bill per shop | `MultiShopCheckoutTest`; and the app now pays each shop separately |
| Out-of-stock visible, no price, cannot be added | Enforced server-side by withholding the number, and pinned in the app |
| Shop-specific stock and pricing | `ShopProductVariant` |
| Local-first discovery, preferred shops, Best Deal, final payable comparison | `TwoWaysToFindAShopTest`, 17 tests |
| Tenant isolation | A3 |
| Shop hours and order acceptance | `StoreHoursCheckoutTest` |
| An accepted order stays owed even if the shop later closes | `OrderLifecycle.isAccepted`, `ALadderNotASwitchTest.onceAcceptedAlwaysOwed` |

### A8. A two-shop testbed you can seed in one command

`scripts/verify/seed_two_shop_testbed.sql`. Every marketplace behaviour worth
testing by hand needs two shops that both reach one address and price the same
item differently; a database with one shop in it can demonstrate none of them.

Verified by running it against a fresh database and querying the result back:

```
Seeded 2 test shops, 4 shop listings, 1 of them out of stock.

TEST-SHOP-A  VERIFIED  8.00 km  7 days of hours  2 listings  0 sold out
TEST-SHOP-B  NONE      8.00 km  7 days of hours  2 listings  1 sold out

TEST-SHOP-A  TEST-DAL-1KG   100.00  stock 50
TEST-SHOP-B  TEST-DAL-1KG    88.00  stock 50     <- undercuts A on the shelf
TEST-SHOP-A  TEST-RICE-5KG  360.00  stock 50
TEST-SHOP-B  TEST-RICE-5KG  375.00  stock  0     <- listed, held: the out-of-stock case
```

Re-running it is a no-op, so a half-finished test session can be topped up
rather than rebuilt.

**Both safety guards were tested, not just written.** Pointed at a database
whose name looks like production it refuses:

```
ERROR: Refusing to seed test data into "seedguard_prod_tmp" - the name looks
       like production.
```

and pointed at a database that already holds trading data it refuses again:

```
ERROR: Refusing to seed: "gpstore_test" already holds 35639 order(s) and
       32372 payment(s). Seeded rows mixed into real trading data cannot be
       picked back out.
```

It creates **no orders, no payments and no money** — those are what you are
meant to make by hand through the app — and **no commercial amounts**, because
those are undecided and a fixture is not the place to invent one. Every row is
named TEST and flagged `is_demo` / `is_test_data`.


### A9. The constraints only a fresh database was missing

The previous edition carried "32 tables are created by both Hibernate and a
migration" as an unaudited risk. All 41 migration-created tables were audited
against a schema built the fresh way.

**Ten CHECK constraints declared inside a `CREATE TABLE IF NOT EXISTS` had
never existed on any newly provisioned database.** Against a table Hibernate
has already made, that whole statement is a no-op, so everything declared
inside it is skipped — present and enforcing on the shop's own database,
absent on every new one. That is the worst shape a schema difference can take:
the environment you would test on is the one without the guard.

| Missing | What it stops |
|---|---|
| `shop_product_variant_price_positive` | A shop listing something at zero or less |
| `ck_shop_rating_range` | A rating outside one to five |
| `ck_shop_rating_hidden_has_a_reason` | A hidden rating with no reason, or a reason with nothing hidden |
| `ck_preferred_slot` | A preferred-shop slot that is not 1 or 2 |
| `ck_dues_amount` | A debt of zero |
| `ck_governance_outcome_needs_an_appeal` | An appeal outcome where nobody appealed |
| `ck_billing_period_dates` | A billing week ending before it starts |
| `ck_billing_plan_dates` | A plan that ended before it began |
| `ck_billing_plan_bps` | A commission outside 0–100% |
| `ck_billing_plan_fee` | A negative weekly fee |

`V64` adds them with `ALTER TABLE`, guarded on each name so it is a no-op
where they exist. It does **not** drop and recreate the tables:
`FlywayOwnedTableReset` is right for a table nothing references — it is how the
two hours tables were fixed — and wrong here, because dropping
`shop_product_variants` or `billing_plan` CASCADE would take surviving tables'
foreign keys with it and nothing would put them back.

Six other declared constraints are deliberately **not** re-added: Hibernate
emits its own CHECK for every `@Enumerated` column with the same value set, so
they are enforced under another name. Each is listed with the name that covers
it rather than silently skipped.

**Verified three ways.** Fresh database: adds 10, second run adds 0. Against
the test database holding **35,831 orders, 32,558 payments and 25,592 shop
listings**: applied in **28 ms**, all ten present. Fresh bootstrap: head V64,
327 check constraints where there were 317.

**And the class of bug is closed, not just the ten instances.**
`ConstraintsDeclaredInsideCreateTableTest` parses every migration, finds every
named constraint declared inside a `CREATE TABLE` body, and fails the build if
the database does not have it — naming the constraint and the migration that
declared it. Mutation-checked: dropping `ck_shop_rating_range` fails it by
name, and its control proves the existence query can answer no.


---

## B. Verified against a deployed backend

**38 checks, 38 passed, against a running server over real HTTP.** Not the
Hostinger VPS — a locally deployed instance of this exact build — but a real
socket, a real registration, a real JWT and real JSON, which is a different
claim from MockMvc and a transaction that rolls back.

`scripts/verify/smoke_api.sh` takes a URL, so the same script runs against
staging or, read-only and carefully, production:

```
scripts/verify/smoke_api.sh http://localhost:8081/v1
scripts/verify/smoke_api.sh https://api.gpstore.co.in/v1
```

What it covered, each with an expected status — a script that only demanded
"not 500" would pass against a server that refused everything:

| Group | Checks |
|---|---|
| Liveness | `/api/health`, `/api/health/ready` → 200 |
| Marketplace, unauthenticated | `mode`, `discovery`, `shops` → 200; a shop the marketplace does not show → **404, not 403** (whether a shop is suspended is between the platform and that merchant) |
| Authentication | register → token issued |
| A customer's own surfaces | cart, cart-by-shop, orders, addresses, preferred-shops, categories, feed, instant search → 200 |
| **A customer is not a merchant** | `/api/shop/profile`, `/listings`, `/earnings`, `/staff`, `/governance` → **403** |
| **A customer is not a platform admin** | `/api/platform/overview`, `/merchants`, `/shops`, `/api/admin/workers` → **403** |
| **A customer cannot moderate or waive** | `/api/shop-ratings/manage`, `/api/cancellation-dues/outstanding` → **403** |
| **IDOR** | somebody else's order, payment, invoice → **403 or 404, never 200** |
| **`X-Shop-Id` narrows, never grants** | merchant routes with another shop's id, and with a made-up one → **403** |
| No token | cart, orders → **401** |
| **The client cannot assert payment** | `POST /verify` with `{"payment_success":true}` → refused; the route takes no body and asks the provider |

**The script is known to be able to fail**, which is the only reason a clean
run means anything: it caught a route this report's author guessed wrong — a
404 where 403 had been written — and refused to pass until the route was
corrected.

Run against a fresh database seeded by `seed_two_shop_testbed.sql`, booted
under `DDL_AUTO=validate` on the V64 schema, with three shops serving the test
pin.

### What is still NOT verified against a deployed backend

- **The Hostinger VPS itself.** `api.gpstore.co.in` is refused by this
  environment's proxy (403 at CONNECT), so production was never contacted and
  no claim is made about it.
- **Merchant-against-merchant over HTTP.** The smoke proves a *customer* is
  refused every private surface. Merchant A being refused Shop B's data is
  proved by the suite (section D), not by a live token — creating a second
  merchant and a worker over HTTP needs platform-admin orchestration the
  script deliberately does not do.
- **Checkout and payment**, which the script will not exercise because it
  would place orders and touch payment rows.

### A staging stack on the VPS you already have

You do not need a second machine. `backend/docker-compose.staging.yml` runs a
second Compose project on the same box — its own database, its own Redis, its
own subdomain, sharing the Traefik already running:

```bash
cp .env.example .env.staging        # then edit; never copy production's .env
docker compose -p gpstore-staging \
  -f docker-compose.yml -f docker-compose.staging.yml \
  --env-file .env.staging up -d
```

Validated with `docker compose config`: exactly three services start
(backend, postgres, redis), Traefik and the docker socket proxy are excluded
so nothing fights over :443, backups are switched off so the testbed does not
write to the disk production's backups land on, and the Traefik router is
named `gpstore-staging` with priority 5 against production's 10 — so a
hostname misconfigured on both resolves to production rather than quietly to
the testbed. It refuses to start at all without `API_DOMAIN`.

**What you are accepting by doing this.** Staging shares production's CPU,
memory, disk and kernel. A staging run that fills the disk fills production's.
A staging load test competes for production's CPU. The isolation is the
database and the process, and nothing below that. The limits total 1,664 MB
against production's 5,440 MB of a KVM 2's 8 GB, which leaves about 900 MB for
the OS — it fits, and it is tight. **Do not raise them to match production's.**


---

## C. Verified on a real Android device

**Nothing.**

Two independent reasons, both checked in this session rather than assumed:

1. **No APK could be built.** The real command was run:

```
flutter build apk --release --split-per-abi --target-platform android-arm,android-arm64 \
  --flavor customer -t lib/customer_main.dart \
  --dart-define=GPSTORE_APP=customer --dart-define=APP_ENV=production \
  --dart-define=API_BASE_URL=https://api.gpstore.co.in/v1
```

```
[!] No Android SDK found. Try setting the ANDROID_HOME environment variable.
$ ls frontend/build/app/outputs/flutter-apk/
ls: cannot access '...': No such file or directory
```

`ANDROID_HOME` and `ANDROID_SDK_ROOT` are unset, there is no SDK directory, no
`sdkmanager`, no `adb`, and `dl.google.com` answers **403** through this
environment's proxy. **No application code was changed to hide this.**

2. **I have no access to your phone, and never will from here.** Even given an
   APK, installing it, tapping through it and reporting what happened are
   things only you can do. Any "device test result" from me would be invented.

The step-by-step procedure for you to run is in H.

---

## D. Verified by load test

Run with the repository's own k6 suite. The Python harness added earlier in
this work was a duplicate of `load-tests/` and was **deleted** rather than kept
beside it; what was genuinely missing — the marketplace read path — is now
`load-tests/marketplace-capacity.js`, in the same style as its siblings. It
**refuses to start** if no shop serves the pin it is given, because discovery
against an empty point answers in a millisecond and would look like the fastest
stage ever recorded.

**Environment: a 4 vCPU / 16 GB container, with the load generator on the same
four cores as the server. NOT the Hostinger VPS.**

Marketplace read path:

| VUs | discovery p95 | discovery p99 | shops-near p95 | storefront p95 | 502 | unexpected 503 | network errors |
|---|---|---|---|---|---|---|---|
| 25 | 203 ms | 218 ms | 32 ms | 24 ms | 0 | 0 | 0 |
| 50 | 168 ms | 180 ms | 14 ms | 16 ms | 0 | 0 | 0 |
| 100 | 228 ms | 251 ms | 10 ms | 9 ms | 0 | 0 | 0 |
| 250 | 284 ms | 317 ms | 10 ms | 8 ms | 0 | 0 | 0 |
| 500 | 460 ms | 538 ms | 9 ms | 8 ms | 0 | 0 | 0 |
| 1000 | 1.12 s | 1.27 s | 14 ms | 10 ms | 0 | 0 | 0 |

At 1000 VUs over 30 s: **12,146 requests, 0 failed (0.00% error rate),
300.2 req/s**, p50 5.5 ms, p95 744 ms, p99 1.18 s across all endpoints. JVM
resident **991 MB**, JVM CPU ~66%, peak Postgres connections **26**. Database
CPU was not separable from application CPU on a shared container and is **not
reported**.

Browse path (`staged-capacity.js`, unchanged):

| VUs | categories p95 | feed p95 | requests | failed | throughput |
|---|---|---|---|---|---|
| 250 | 168 ms | 5 ms | 2,070 | 0.00% | 70 req/s |
| 1000 | 301 ms | 2 ms | 8,201 | 0.00% | 278 req/s |

**This does not mean "supports 1000 concurrent users", and that phrase appears
nowhere in this repository.** A k6 VU is a shopper with 1.5–4 s think time,
which is why 1000 of them produced 300 req/s and not 1000. Discovery is the
only read that grows with load. These are a **floor** on a container, not a
capacity figure for the VPS.

**Production was not load-tested and must not be.**

---

## E. Not verified

Each of these is a real gap, not a formality.

1. **Everything in objective 5** — health, readiness, auth, discovery,
   progressive radius, shop profile, compare, preferred shops, product listing,
   variants, sold-out behaviour, basket, per-shop checkout, order creation,
   history, cancellation, refund state, worker status flow, merchant isolation,
   platform-admin visibility — **against a deployed URL**. Blocked by B.
2. **The hand-run IDOR attacks of objective 6 against a running server.**
   Covered by the suite (A3), not by a live deployment.
3. **Any device testing** (objective 8). Blocked by C.
4. **Checkout and payment driven through the UI.** The rule that every shop is
   charged is unit-tested (`ordersToPay`); the gateway SDK is not exercisable in
   a widget test.
5. **Merchant-side and worker-side screens driven end to end.** Backend-verified
   only.
6. **The invoice/receipt screen, the browse grid, and search screens.**
7. **Return, pickup and refund through the UI.**
8. **Load on the Hostinger VPS.**
9. ~~A migration meeting real production data.~~ **Done for V64**: applied to
   a database holding 35,831 orders, 32,558 payments and 25,592 shop listings,
   in 28 ms, all ten constraints present afterwards. Earlier migrations still
   rely on CI's rehearsal job.

---

## F. Blockers

| # | Blocker | Class | What would clear it |
|---|---|---|---|
| 1 | **No staging environment exists** | INFRASTRUCTURE MISSING | The five items in B |
| 2 | **No Android SDK; `dl.google.com` 403 through the proxy** | ENVIRONMENT BLOCKED | Build on a machine with the SDK — command in H |
| 3 | **No device access, ever, from this environment** | INHERENT | Only you can run H |
| 4 | **Collection contradicts Decision W1** — one platform account holds every shop's product money, when W1 says a merchant's proceeds are the merchant's | RULE DECIDED; **provider, KYC, settlement, fees and refunds** are not | Your provider and legal advice: choose a provider, an onboarding/KYC flow, a settlement mechanism, fee and refund treatment. The *rule* needs nothing from you — it is already recorded |
| 5 | **`api.gpstore.co.in` unreachable from here** (403 at the proxy) and **no SSH key in this container** | ENVIRONMENT + CREDENTIAL BLOCKED | Not needed if staging exists; production deploys on merge to `main`, which is your call |
| 6 | **No commercial amounts exist** — cancellation is free for everyone, merchants cannot set fee terms | FOUNDER DECISION | Set `platform.cancellation.max-fee-percent` and the billing amounts |
| 7 | **Governance and reliability thresholds are unapproved defaults** | FOUNDER DECISION | Six properties, no release needed |
| ~~8~~ | ~~32 tables created by both Hibernate and a migration~~ | **CLOSED** | Audited; see A9. Ten missing constraints added by V64, and a test now fails the build if another is written inside a `CREATE TABLE` |
| 9 | **Escalation, intervention recovery and merchant pickup not built** | FOUNDER DECISION | Deliberately not stubbed — "where policy permits" names a policy that does not exist |

---

## G. Production risks

1. **Money currently flows the wrong way.** One platform account carries every
   shop's product money. That is not an undecided question: Decision W1
   (`docs/architecture/03-decision-w1-money-model.md`, 2026-09-05) already
   records the requirement — each merchant's product proceeds belong to that
   merchant, and GP-STORE is a technology marketplace rather than a payment
   aggregator holding everybody's money in one account. The code does not
   honour that decision yet, so this is a **gap against a recorded decision**.
   The seam is real and *refuses* rather than lying — `prepareCheckout` throws
   for any shop configured to collect its own payments, and the application
   logs the non-conformance at startup. What is genuinely open is only *how*
   to honour W1 compliantly: the payment provider, merchant onboarding and
   KYC, the settlement mechanism, how fees are borne, and how refunds are
   funded and reversed (F4). The boundary stays provider-agnostic until those
   are settled. **This is the largest risk on the list.**
2. **The app has never run against a real backend.** Every marketplace screen
   added in this phase is widget-verified against a scripted contract. A
   mismatch between that contract and the live server would surface on a
   customer's phone first.
3. **A migration has never met production data in this phase.** The fresh-
   database path is proven; the rehearsal-against-rows job exists and was not
   run here.
4. **Capacity on the VPS is unknown.** The numbers in D are from a container.
5. ~~32 unaudited dual-created tables~~ — **closed this round.** All 41 were
   audited, the ten genuinely missing constraints are added by V64, and the
   class of bug now fails the build.
6. **No merchant notification channel.** A shopkeeper learns of a governance
   action by looking.

---

## H. What you can do next, exactly

### H0. You can test from your phone TODAY, without a staging VPS

This is the shortest path to a real two-shop test and it needs no new
infrastructure — only a computer with the Android SDK, on the same wifi as
your phone.

```bash
# 1. A throwaway database, migrated and seeded
createdb gpstore_testbed
cd backend
DB_URL=jdbc:postgresql://localhost:5432/gpstore_testbed DDL_AUTO=update \
  FLYWAY_ENABLED=true ./mvnw -q -Pschema-bootstrap \
  -Dtest=EmptyDatabaseBootstrapTest -DexcludedGroups= test
psql -d gpstore_testbed -f ../scripts/verify/seed_two_shop_testbed.sql

# 2. Run the backend on your computer, reachable on the LAN
DB_URL=jdbc:postgresql://localhost:5432/gpstore_testbed \
DB_USERNAME=... DB_PASSWORD=... JWT_SECRET=<any long throwaway string> \
  java -jar target/backend-0.0.1-SNAPSHOT.jar
# find your computer's LAN address, e.g. 192.168.1.20

# 3. Build the APK pointed at your computer, NOT at production
cd ../frontend
flutter build apk --release --split-per-abi --target-platform android-arm64 \
  --flavor customer -t lib/customer_main.dart \
  --dart-define=GPSTORE_APP=customer \
  --dart-define=APP_ENV=staging \
  --dart-define=API_BASE_URL=http://192.168.1.20:8081/v1
```

`APP_ENV=staging` **refuses** to fall back to the production URL and
**throws** if you hand it the production one, so this build cannot
accidentally talk to the live shop.

Two things to know before you trust the result: the seeded shops sit at
27.16231, 83.940468, so your test address must be within 8 km of that or
discovery will correctly return nothing; and a plain-HTTP LAN address needs
`usesCleartextTraffic` for that build, which this repo does not set for
release — so use an HTTPS tunnel to your machine, or run the staging build
in debug mode, rather than changing the manifest.

This proves the app against a **real server over a real network** — which is
most of what the device test is for. It does not prove the Hostinger VPS, and
it does not prove payment.


### H1. Build the APK, on a machine with the Android SDK

```bash
cd frontend
flutter build apk --release --split-per-abi --target-platform android-arm,android-arm64 \
  --flavor customer -t lib/customer_main.dart \
  --dart-define=GPSTORE_APP=customer \
  --dart-define=APP_ENV=production \
  --dart-define=API_BASE_URL=https://api.gpstore.co.in/v1
# → build/app/outputs/flutter-apk/app-arm64-v8a-customer-release.apk
```

**For a staging build, point it at staging instead** — the app already supports
this and refuses to guess:

```bash
  --dart-define=APP_ENV=staging \
  --dart-define=API_BASE_URL=https://staging-api.gpstore.co.in/v1
```

`APP_ENV=staging` **throws at build time** if the URL given is the production
one, and throws if no URL is given at all, rather than silently targeting the
live shop. No URL is hard-coded in source for either case.

### H2. Before installing anything, check the backend is the one you expect

```bash
curl https://<host>/v1/api/health        # → GP-STORE Backend Running Successfully!
curl https://<host>/v1/api/health/ready  # → {"status":"ready"}
curl "https://<host>/v1/api/marketplace/mode"
curl "https://<host>/v1/api/marketplace/discovery?lat=<your lat>&lng=<your lng>"
```

If `mode` says `SINGLE_SHOP`, no shop switcher is drawn and none of the
two-shop flow applies. If `discovery` returns `[]`, no shop reaches your
address — a correct answer that will look like a broken app.

### H3. The customer journey to run on the phone

Login · discover shops · Shop A profile · browse · **an out-of-stock item shows
no `₹` and cannot be added** · Shop B · Compare Other Shops · check the
**final payable** decides, not the shelf price · star a preferred shop · add
from both shops · **basket separated with each shop's own delivery** ·
checkout.

**At checkout you should be sent to the gateway twice, labelled "shop 1 of 2"
and "shop 2 of 2".** One gateway visit for a two-shop basket means the defect
fixed in this phase has reappeared — stop and report it. Then: two orders in
history, two shops, two statuses; the 5-second cancellation countdown; and
**cancelling must be free** — any fee means a commercial number got invented.

### H4. Merchant, worker, platform admin

Merchant: only Shop A's data anywhere — products, stock, prices, orders, hours,
workers, governance, ratings, refunds. Setting a cancellation fee must be
**refused with an explanation**. Worker: only own assignments; Packed → Ready →
Out for delivery → Delivered, in order, skipping refused. Platform admin: both
shops, no customer secrets.

### H5. Security, by hand

With a merchant session, change the id and re-issue:

```
GET  /v1/api/orders/{another shop's order id}
GET  /v1/api/shop-ratings/manage          with X-Shop-Id: <another shop>
POST /v1/api/cancellation-dues/{someone else's due}/waive
GET  /v1/api/shop/governance              with X-Shop-Id: <another shop>
GET  /v1/api/platform/governance/actions  as a shop owner
```

**Every one must fail with 403 or 404.** A 200 carrying someone else's data is
release-stopping. These are the exact routes the nine tests in A3 attack.

---

## Readiness score

**75 / 100**, up from 66.

### What moved, and why

| +/- | |
|---|---|
| **+4** | The dual-created-table risk is **closed**, not merely described: ten constraints that no new environment had are back, verified against 35,831 real orders, and the class of bug now fails the build |
| **+3** | Verification against a **running server over real HTTP** exists for the first time — 38 checks including the whole authorisation matrix and IDOR — and the script points at any URL, so it is reusable on the VPS |
| **+1** | A migration met real data, a two-shop testbed can be seeded in one command, and a staging stack for the VPS you already own is written and `docker compose config`-validated |
| **+1** | The 0%-commission-on-delivery rule is pinned by a mutation-checked test instead of living unremarked in two lines of SQL |

### Why it is not higher, item by item

**None of the remaining points is code, and none of them moves by adding
tests.** This is the honest arithmetic rather than a re-weighted scale:

| Withheld | Worth roughly | Who can clear it |
|---|---|---|
| Collection does not honour Decision W1 — one platform account holds every shop's product money | **10** | You, with your provider and legal advice: provider, onboarding/KYC, settlement, fees, refunds |
| Never run on a real device against a real backend | **6** | An Android SDK machine + your phone |
| Not deployed — this is on a branch; production runs the previous build | **4** | You; merging to `main` is a live-shop decision |
| Load measured on a container, not the VPS | **3** | The VPS, once a staging stack exists |
| No commercial amounts; no approved governance thresholds | **2** | You — inventing them is barred, and rightly |

**25 points, all of them yours.** The engineering side is close to exhausted:
there is no remaining defect in this report that I can fix without one of your
decisions or a machine this environment does not have.

### What it is made of now

| | |
|---|---|
| Tenancy, isolation, authorization | strong — attacked in the suite AND by hand over HTTP, with positive controls, mutation-checked |
| Order lifecycle | a resurrection bug and a wrongful-cancellation bug are gone; one guarded door |
| Schema and provisioning | impossible → verified → **constraint-parity proved and guarded** |
| Customer-facing marketplace | functional; widget-verified; API-verified over HTTP; **not on a device** |
| Payment safety | boundary structurally sound and provider-agnostic; **the implementation does not yet honour Decision W1** |
| Performance | measured, on the wrong hardware |
| Commercial model | deliberately absent |
| Deployed | **no** |

**Do not read a green suite as permission to take real money through this.**
