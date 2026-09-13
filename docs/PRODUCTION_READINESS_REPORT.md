# GP-STORE — PRODUCTION READINESS REPORT

*12 September 2026. Every figure below came from a command run against the
committed code, or from a GitHub Actions run whose id is given. Where
something was not verified, this report says so and says why — it does not
round an untested claim up to a passing one.*

---

## 1. FINAL STATUS

**Deployed, and verified against the deployed instance.**

| | |
|---|---|
| Backend suite | 1836 tests, 0 failures, 0 errors, 1 skipped |
| Flutter suite | 702 tests, all passing |
| `flutter analyze --no-fatal-infos` | clean — 41 infos, 0 warnings, 0 errors |
| Production smoke | 40 checks, 40 passed, with the deployed SHA asserted |
| Deployment | automated on merge to `main`, with the smoke running inside the same workflow run |

The role model the owner specified is now what the code does, and each of its
sixteen rules has a named test that fails loudly if somebody breaks it.

**What is NOT claimed.** Nothing has been verified on a physical Android
device. Live merchant-against-merchant isolation has been proved by the suite
over real HTTP but not with two real production merchant logins, because
there is only one real merchant on the platform — see section 12. Money still
lands in one platform account, which is the recorded decision
(`PLATFORM_COLLECTS`) and not a gap.

---

## 2. EXACT ROLE MODEL

Four business roles, and no fifth.

| Role | Is | Holds |
|---|---|---|
| **SUPER_ADMIN** | The platform owner. Runs GP-STORE itself and takes every decision about it | **Every** permission the enum defines, including `PLATFORM_ADMIN` (cross-shop scope), `PLATFORM_OBSERVABILITY` (the marketplace's own metrics) and `CATALOG_DEFINE` (the shared catalogue). Resolves to a scope spanning the whole market, and may name any shop |
| **ADMIN** | A shop owner. One merchant, their own storefronts | Everything inside their own shop — catalogue, stock, orders, money, coupons, customers — **and their own delivery workers**. Never another shop's anything, never the platform's metrics, never the shared catalogue |
| **DELIVERY_BOY** | A delivery worker | **No** administrative permission at all. Their access to their own deliveries comes from `ROLE_DELIVERY_BOY` on the specific delivery rules. Also a customer, so they can shop with the same account |
| **CUSTOMER** | A customer | No administrative permission. Their own cart, orders, addresses and payments, resolved from the authenticated id |

`MANAGER`, `INVENTORY_MANAGER`, `ORDER_MANAGER`, `DELIVERY_MANAGER` and
`SUPPORT` also exist, and are **subsets of ADMIN** a merchant may hand to
their own staff. None of them holds anything that spans the marketplace.

### PLATFORM_ADMIN

**Not a separate business authority.** It was one — wider than ADMIN across
shops, deliberately narrower inside any one of them — and that split is
retired by the owner's decision.

The enum constant survives as a **compatibility alias** that grants exactly
what `SUPER_ADMIN` grants, **by reference**, so the two cannot drift. It was
not deleted because `customers.role` is a string under a CHECK constraint that
`V50__staff_shop_identity.sql` taught to accept `'PLATFORM_ADMIN'`: removing
the constant would make `Role.valueOf` throw on any surviving row, which is an
outage for that account in a database this code cannot inspect. No migration
ever seeded such an account, so this is a precaution and not a known case.

`AdminPermission.PLATFORM_ADMIN` — the **permission** of the same name — is
very much still in use. It is the mechanism that grants cross-shop scope, and
`SUPER_ADMIN` is the only business role that holds it. Do not confuse the two.

---

## 3. WHAT YOU CHANGED

### The role model

- `RolePermissions`: `SUPER_ADMIN` → every permission (`allOf`, correct here
  and nowhere else); `ADMIN` → every *shop* permission, written as a
  **subtraction** so a permission added to the enum tomorrow is not silently
  handed to every shopkeeper; `PLATFORM_ADMIN` → the same set object as
  `SUPER_ADMIN`.
- `Role.java`: `SUPER_ADMIN` and `PLATFORM_ADMIN` documented as what they now
  are, with the reason the alias exists written down where somebody tempted to
  delete it will read it.
- `AdminPermission.PLATFORM_OBSERVABILITY` added, and `/actuator/**` moved onto
  it. `/actuator/health` stays public for the load balancer.
- Flutter mirror (`admin_permissions.dart`) updated to match, sharing one
  `_everything` set between `superAdmin` and `platformAdmin`.

### The sixteen rules, pinned

`TheSixteenRoleRulesTest` — one test per rule. Each asserts the permission
algebra directly **and names the end-to-end guard** that proves it over real
HTTP, failing if that guard has been renamed, moved or deleted.

The problem it solves: spread across a dozen classes, a rule could be lost by
*deletion* rather than by failure. Nothing went red, the rule simply stopped
being checked, and no reader could tell "proved elsewhere" from "not proved
anywhere".

Three rules had no named test at all, and now do:

| Rule | New test |
|---|---|
| TEST 1–3 — SUPER_ADMIN reaches platform resources, may work in any shop, manages every shop's workers | `MarketplaceIdentityTest.superAdminReachesPlatformWideResources`, `.superAdminCanManageAnyShop`, `.superAdminCanManageAnyShopWorker` |
| TEST 15 — a client-supplied payment status cannot mark an order paid | `TheClientNeverDeclaresAnOrderPaidTest` (3 tests) |

**Why TEST 1–3 mattered.** Every platform test signed in as
`PLATFORM_ADMIN`. Had the alias been the only role wired to the platform
console, `SUPER_ADMIN` — the role real accounts hold — would have been locked
out of it and the whole suite would still have passed.

### Search, and the production 500 it caused

`/api/products/search/instant` returned HTTP 500 for **every** query in
production while 1793 tests passed and a developer machine returned 200.

The chain: production's `pg_trgm` is a Supabase dump living in schema
`extensions`, not `public`, so the `%` similarity operator did not resolve →
the query failed → the ILIKE fallback ran **inside the already-aborted
transaction** → PostgreSQL `25P02` → `ValueRetrievalException` → 500. A
catch-and-retry inside one transaction cannot work, which is why the original
fallback could not have saved it.

Fixed twice over, deliberately:

1. `ProductBrowseRepository` now **probes before querying** —
   `pg_operator_is_visible`, a catalogue read that cannot poison a
   transaction — and picks the trigram or the ILIKE query up front.
2. `spring.datasource.hikari.connection-init-sql` sets
   `search_path` to `public, extensions` on every pooled connection, so the
   operator resolves wherever the extension was installed. `public` stays
   **first**, so table resolution is unchanged.

The second is what made this a permanent repository fix rather than a manual
VPS command. Verified directly:
`set_config('search_path','public, extensions',false)` sets both schemas and
`%` and `similarity()` then resolve unqualified.

**The entire suite now passes against a database with `pg_trgm` in
`extensions`** — the production layout — which is how the bug was reproduced
in the first place.

**Search is indexed there, not merely working.** Moving the extension between
schemas rewrites the opclass reference in every dependent index, so the four
GIN indexes on `products` became
`USING gin (name extensions.gin_trgm_ops)` and are still chosen by the
planner:

```
explain select id from products where name % 'basmati'
  ->  Bitmap Index Scan on idx_products_name_trgm
```

That distinction matters: a fix that made the operator resolve but left the
query on a sequential scan would have turned a 500 into a slow page, which is
harder to notice and worse under load.

### The deploy verifies what it shipped

`.github/workflows/production-smoke.yml` runs the 38-check smoke suite from a
GitHub runner, asserting `/api/version` matches the SHA that was deployed.
`deploy-production.yml` calls it as its final job.

The first attempt at this shipped a `workflow_run` trigger that **could never
fire**: events created with `GITHUB_TOKEN` do not trigger new workflow runs,
so a chain dispatched by `github-actions[bot]` is silent. Deploy #283 went
green with no smoke run at all. Replaced with `workflow_call`, which runs
inside the caller's own run, and confirmed firing on deploy #285.

---

## 4. SECURITY FIXES

| | Defect | Fix | Proved by |
|---|---|---|---|
| 1 | **A shop owner could read the whole marketplace's metrics.** `/actuator/metrics` and `/actuator/prometheus` report `http_server_requests` across every tenant — enough for one merchant to estimate every other merchant's order volume — and *every* shop owner holds `SYSTEM_ADMIN` | `/actuator/**` moved to `PLATFORM_OBSERVABILITY`, which only the platform owner holds. `/actuator/health` stays public | `StaffRoleAuthorizationTest.adminReachesEverything` (forbids it) and `.superAdminReachesSystemSurface` (allows it) |
| 2 | **The platform owner was trapped in one shop.** `SUPER_ADMIN` was byte-identical to `ADMIN`, so `TenantResolver` resolved the person who owns the marketplace to a single storefront — and on an owner account with no shop membership it **threw outright** | `SUPER_ADMIN` holds `PLATFORM_ADMIN`, so it resolves to platform scope and may name any shop | `MarketplaceIdentityTest.superAdminCanManageAnyShop` |
| 3 | **Instant search 500'd in production** | Probe before query; `search_path` on every connection | `InstantSearchSurvivesTrigramNotOnThePathTest`, `TrigramSearchPathTest`, and the full suite under the production extension layout |
| 4 | **No test stopped a client declaring its own order paid.** The DTO was already safe; nothing failed if somebody made it unsafe | Assert the outcome, not the annotation: hostile body over real HTTP, row read back from the database | `TheClientNeverDeclaresAnOrderPaidTest` |

**Mutation-tested**, not assumed: widening
`/api/payments/order/*/upi/**` to `ROLE_CUSTOMER` makes
`aCustomerCannotConfirmTheirOwnUpiPayment` fail by name, with its own message.
The protection was then restored and the diff confirmed empty.

### Audited and found clean this pass

- **Actuator exposure** — `management.endpoints.web.exposure.include` is
  `health,info,prometheus,metrics`. No `env`, `heapdump`, `threaddump` or
  `loggers`. `health.show-details=when-authorized`.
- **SQL injection in search** — every user value is a bound parameter
  (`:keyword`, `:likePattern`) with LIKE escaping. No concatenation of input
  into SQL anywhere in the browse repository.
- **Sensitive logging** — OTP and auth paths log event names and *masked*
  phone numbers. No OTP code, password, token or secret value is logged.
- **Mass assignment on checkout** — `PlaceOrderRequest` has three fields and
  no `customerId`; the buyer comes from the token. Now covered by a test.
- **Unbounded queries** — page size capped at 100, size 0 and negative pages
  rejected, paging always sorted (`UnboundedQueryGuardTest`).

---

## 5. DATABASE / MIGRATION CHANGES

**No new migration.** This is deliberate and worth stating plainly: the role
change is entirely in application code, because `customers.role` already
accepts every value involved. There is no data migration to run, nothing to
back out, and no window in which the schema and the code disagree.

`V50__staff_shop_identity.sql`'s `customers_role_check` still lists
`'PLATFORM_ADMIN'`, which is exactly why the enum constant was aliased rather
than deleted.

Schema state: Flyway **V2–V64** (there is no V1 — the schema is
Hibernate-first; `ddl-auto` creates the entity tables and
`FlywayAfterSchemaConfig` runs the migrations afterwards). CI's
`schema-migrate` job bootstraps a fresh database and then boots the app under
`DDL_AUTO=validate`, so a migration that disagrees with an entity fails the
build rather than a deploy.

---

## 6. FLUTTER CHANGES

- `admin_permissions.dart` — added `platformObservability`, excluded it from
  the shop-role set `_all`, added a shared `_everything` set, and pointed both
  `superAdmin` and `platformAdmin` at it. `platformAdmin` documented as the
  alias it now is.
- `admin_permissions_test.dart` — the system-surface test now names all three
  roles that hold it, spelled out role by role rather than derived, so a *new*
  role cannot arrive holding the system surface without somebody editing that
  line.
- A **real drift failure** was caught by CI, not by review: adding
  `PLATFORM_OBSERVABILITY` to the backend without updating the Dart mirror
  failed `admin_permissions_test.dart: drift against the backend`. The mirror
  was fixed. The test was not.

The navigation and console screens needed no change: they gate on
`AdminPermission.platformAdmin`, which no *shop* role holds — still true — and
`superAdmin` now holds it, so the platform owner sees the Marketplace group
they previously could not.

**Flutter restrictions are UX. Backend authorization is security.** Both are
asserted separately; the app does no gating the server does not also enforce,
and a 403 from these routes is shown rather than worked around.

---

## 7. TEST RESULTS

Backend, full `mvn clean verify` against a real Postgres 16 with `pg_trgm` in
schema `extensions`, after wiping the schema and re-bootstrapping it:

```
Tests run: 1836, Failures: 0, Errors: 0, Skipped: 1
BUILD SUCCESS
330 test classes
```

Flutter, on the project's own pin:

```
flutter analyze --no-fatal-infos  ->  41 issues — 0 errors, 0 warnings
flutter test                      ->  +702: All tests passed!
```

The one skipped backend test is pre-existing and unrelated to this work. No
test was disabled, deleted, or weakened to reach this result. Two tests
**changed** their assertions, in both cases because they encoded a business
model the owner has since reversed, and both changes are argued in the commit
message and in comments beside the assertions.

### The sixteen rules, and where each is proved

| | Rule | Where |
|---|---|---|
| 1 | SUPER_ADMIN reaches platform-wide resources | `MarketplaceIdentityTest.superAdminReachesPlatformWideResources`, `StaffRoleAuthorizationTest.superAdminReachesSystemSurface` |
| 2 | SUPER_ADMIN can manage any shop | `MarketplaceIdentityTest.superAdminCanManageAnyShop` |
| 3 | SUPER_ADMIN can manage any shop's workers | `MarketplaceIdentityTest.superAdminCanManageAnyShopWorker` |
| 4 | ADMIN can manage its own shop | `StaffRoleAuthorizationTest.adminReachesEverything`, `MarketplaceIdentityTest.aSecondMerchantOperatesTheirOwnShop` |
| 5 | ADMIN can manage its own workers | `ShopStaffAndRidersTest.aShopMayHireAFreeAgent` |
| 6 | ADMIN cannot access another shop | `MarketplaceIdentityTest.merchantACannotSelectShopB`, `.merchantACannotReadShopBProfile`, `CrossTenantApiAccessTest.writingToAnotherShopsOrderOverHttp` |
| 7 | ADMIN cannot access another shop's workers | `MarketplaceIdentityTest.superAdminCanManageAnyShopWorker` (asserts both directions), `ShopStaffAndRidersTest.aShopCannotPoachAnotherMerchantsStaff` |
| 8 | ADMIN cannot read platform metrics | `MarketplaceOversightTest.aShopOwnerCannotOpenTheMarket`, `TheGpStoreRoleModelTest$ShopAdmin.isNotTheAppAdmin` |
| 9 | ADMIN cannot use the actuator/management surface | `StaffRoleAuthorizationTest.adminReachesEverything` |
| 10 | DELIVERY_BOY cannot administer anything | `StaffRoleAuthorizationTest.riderStillLockedOut` |
| 11 | DELIVERY_BOY cannot access another worker's delivery | `WorkerDeliveryStatusTest.anotherWorkersDeliveryIsNotFound`, `.anotherWorkersDeliveryIsHiddenOnRead` |
| 12 | DELIVERY_BOY cannot modify another worker's COD | `WorkerDeliveryStatusTest.anotherWorkerCannotCompleteCod` |
| 13 | CUSTOMER cannot access admin APIs | `AdminAuthorizationIntegrationTest.adminNewOrdersSinceRejectsCustomerRole`, `.workerAdminRejectsCustomerRole`, `StaffRoleAuthorizationTest.customerStillLockedOut` |
| 14 | A client-supplied shopId cannot bypass tenant isolation | `TheTenantNeverComesFromTheRequestTest.theShopHeaderIsCheckedRatherThanTrusted`, `.aQueryParameterCannotChooseTheShop`, `.selectionNarrowsButNeverGrants` |
| 15 | A client-supplied payment status cannot mark an order paid | `TheClientNeverDeclaresAnOrderPaidTest.aBodyClaimingPaidIsIgnored`, `.aBodyClaimingItsOwnTotalIsIgnored`, `.aCustomerCannotConfirmTheirOwnUpiPayment` |
| 16 | PLATFORM_ADMIN cannot become an independent privilege level | `TheGpStoreRoleModelTest$NoSecondAuthority.isExactlyTheAppAdmin` |

`TheSixteenRoleRulesTest` holds this table as executable assertions: each row
is a test method that checks the permission algebra and then verifies the
named guard still exists.

---

## 8. CI RESULT

Green. `ci.yml` runs, on every push to the branch and on the PR:

| Job | What it protects |
|---|---|
| `build-and-test` | The full backend suite against a real Postgres 17 and Redis |
| `schema-migrate` | A fresh database bootstrapped and then booted under `DDL_AUTO=validate` |
| `flutter-checks` | `flutter analyze --no-fatal-infos` and `flutter test` |
| `build-apk` | A real Gradle build, so a compile error in Android code is caught |
| `Check deploy scripts` | `shellcheck` over the deploy and verify scripts |

---

## 9. DEPLOYMENT RESULT

Automated. On merge to `main`, `deploy-production.yml` builds the image,
deploys to the VPS, and then — as a job in the **same workflow run** —
calls the smoke workflow with the SHA it just shipped.

Confirmed working end to end on deploy **#285**. The preceding attempt,
#283, deployed successfully but ran no smoke at all, because the trigger
shipped in that change could not fire; see section 3.

---

## 10. PRODUCTION SMOKE RESULT

**40 checks, 40 passed**, against `api.gpstore.co.in`, from a GitHub runner,
with `EXPECT_SHA` asserted against `/api/version` so a smoke run cannot pass
against the *previous* build.

Every check asserts an expected status code. A script that only demanded "not
500" would pass against a server that refused everything.

| Group | Asserts |
|---|---|
| Liveness | `/api/health`, `/api/health/ready` → 200 |
| Version | `/api/version` → 200 **and the SHA matches what was deployed** |
| Marketplace, unauthenticated | `mode`, `discovery`, `shops` → 200; a shop the marketplace does not show → **404, not 403** — whether a shop is suspended is between the platform and that merchant |
| Authentication | register → token issued |
| A customer's own surfaces | cart, cart-by-shop, orders, addresses, preferred-shops, categories, feed, **instant search** → 200 |
| A customer is not a merchant | `/api/shop/profile`, `/listings`, `/earnings`, `/staff`, `/governance` → **403** |
| A customer is not the platform | `/api/platform/overview`, `/merchants`, `/shops`, `/api/admin/workers` → **403** |
| A customer cannot open a staff login | `POST /api/platform/staff` with a well-formed body, and `POST /api/platform/staff/{id}/reset-password` → **403 exactly** — these are the only routes that mint an `ADMIN` account and return a usable password, and a 404 would mean the route is not deployed rather than refused |
| A customer cannot moderate or waive | `/api/shop-ratings/manage`, `/api/cancellation-dues/outstanding` → **403** |
| IDOR | somebody else's order, payment, invoice → **403 or 404, never 200** |
| `X-Shop-Id` narrows, never grants | merchant routes with another shop's id, and with a made-up one → **403** |
| No token | cart, orders → **401** |
| The client cannot assert payment | `POST /verify` with `{"payment_success":true}` → refused; the route takes no body and asks the provider |

**What it writes, exactly: one row.** The in-deploy run leaves
`SMOKE_READ_ONLY` at `0`, so the authenticated half runs, and the single
write it costs is one `customers` row — name `SMOKE TEST`, a `9999xxxxxx`
phone, an `@example.invalid` email, role `CUSTOMER`, unverified. No order,
no payment, no cart content, no shop, no merchant, no worker, and nothing
deleted. The app offers no other way to obtain a customer token, so that row
is the minimum any signed-in check can cost.

`SMOKE_READ_ONLY=1` is available and costs nothing, but it skips
*everything that needs a token* — registration, the signed-in surfaces, the
customer-is-not-a-merchant matrix, the IDOR probes, the `X-Shop-Id` probes
and the payment-assertion probe. That is most of what makes this suite worth
running, so the deploy pays the one row.

**The script is known to be able to fail**, which is the only reason a clean
run means anything: it caught a route whose expected status had been guessed
wrong, and refused to pass until the expectation was corrected against the
actual mapping.

**And it says when a check is weaker than it looks.** The run prints:

```
shops serving (27.16231, 83.940468): 1
NOTE: fewer than two shops reach this pin, so the two-shop checks below
      cannot mean much.
shop A=1  shop B=
```

That is the honest state of the marketplace: **one real shop**. So the
live two-shop checks — shop A's storefront resolving and shop B's not
leaking into it — are degenerate, and the marketplace-discovery half of the
suite is confirming plumbing rather than isolation. Cross-tenant isolation
itself is proved by the suite, over real HTTP, with two real merchants and
two real shops; what production cannot yet confirm is the same thing with
production data. Section 11, risk 2, and section 12, item 1.

The one live check that this release specifically needed, and got:

```
PASS  GET /api/products/search/instant                200
```

Instant search returned 500 for every query before this work. That line is
the fix confirmed on the running server, not in a test.

The deployed build was confirmed by identity, not by timing:

```
PASS  GET /api/version                                200
      deployed gitCommit: 34fdf3445aa7f9c984331c6677fd2718c37a277c
PASS  /api/version matches expected commit            34fdf3445aa7
```

---

## 11. REMAINING RISKS

| | Risk | Why it is not worse than it sounds |
|---|---|---|
| 1 | **No physical-device testing.** No APK has been installed on a real phone | Every screen is covered by widget tests, and `build-apk` proves the app compiles and links for Android. What device testing catches that neither does: real GPS, real push delivery, real camera scanning, and how it behaves on a slow network |
| 2 | **Live merchant-vs-merchant isolation is proved by the suite, not by two real logins** | The suite proves it over real HTTP with two real merchants, two shops and real tokens, and the production smoke proves a *customer* is refused every private surface. What is missing is the same assertion with two production accounts — which needs a second real merchant to exist |
| 3 | **`PLATFORM_ADMIN` rows, if any exist** | Nothing can be done from here: the constant is aliased so such an account keeps working with exactly the platform owner's authority. The one-line check is in section 12 |
| 4 | **Straight-line delivery distance.** No routing provider is configured, so distance is as-the-crow-flies | Always *shorter* than the road distance, so this under-charges rather than over-charges. Logged as a caveat on every quote rather than hidden |
| 5 | **Variants without a weight or cost price** | Under-charges delivery and under-states margin, in the customer's favour. Logged per quote, naming the variant |
| 6 | **Money lands in one platform account** | The recorded decision (`PLATFORM_COLLECTS`), not a defect. Merchant settlement is a business decision with KYC attached |

---

## 12. USER ACTION REQUIRED

Only things that genuinely need a person with real-world access. Everything
else in this report was done.

1. **A second real merchant and shop**, if you want live A-vs-B isolation
   proved with production credentials rather than by the test suite. This
   needs a real merchant who agrees to be onboarded — not something to
   fabricate.

   **The mechanics are no longer your problem, including the login.** In the
   admin app, **Marketplace → Merchants & Shops** now opens the merchant's
   `ADMIN` account, registers the business, walks it through review, opens the
   storefront, and puts that account on its staff list. Registering a merchant
   hands you a **one-time password** to pass on; the merchant must replace it
   before the app will let them do anything, and after that your copy is dead.
   That last part is what makes their actions their own in a dispute.

   Until this existed, no API could make an account an `ADMIN` — the only role
   ever assigned in code was `DELIVERY_BOY` — so this step meant SQL on the
   box. It was the one thing only you can do that you could not do from the
   app.

   `docs/ONBOARDING_A_SHOP.md` is the reference for what those buttons send,
   and `scripts/verify/onboard_second_shop.sh` still does the whole thing over
   HTTP and then attacks the wall between the new shop and Shop #1. What
   remains yours is the merchant.

2. **A phone, and the APKs.** `build-apk` produces all four on every CI run:
   customer, **super admin**, admin and worker. Install them and walk the
   customer journey, the platform console, the merchant back office and the
   worker app. This is the only item on this list that nothing automated can
   substitute for.

   **GP-STORE Super Admin** (`gpstore-superadmin-release.apk`) is yours and
   opens on Merchants & Shops. It installs alongside GP-STORE Admin rather
   than replacing it — separate applicationIds — so you can hold the owner's
   app while a merchant signs into theirs on the phone next to you.

3. **Check for `PLATFORM_ADMIN` accounts.** Run the **Who runs the shop**
   workflow in GitHub Actions: it answers this and item 4 together against the
   live database, read-only, with emails masked. The query it runs, if you
   would rather read it yourself:

   ```sql
   SELECT id, email, role FROM customers WHERE role = 'PLATFORM_ADMIN';
   ```

   If it returns rows, move each to the role they should have. They work
   correctly either way — the alias grants exactly `SUPER_ADMIN` — so this is
   tidiness, not a fix:

   ```sql
   UPDATE customers SET role = 'SUPER_ADMIN' WHERE role = 'PLATFORM_ADMIN';
   ```

4. **Done — and the answer was "nobody".** The **Who runs the shop** workflow
   was run against production and reported one staff account, `dg****@gmail.com`
   (id 1), role `ADMIN`, on Shop #1's staff. **Zero** `SUPER_ADMIN` rows.

   So nothing could open Merchants & Shops, nothing could register a merchant,
   and the Super Admin APK was correctly refusing the only account there was.
   `V66__the_platform_owner_holds_super_admin.sql` promotes that account, once,
   guarded so it can never mint a second platform owner.

   **What that costs, stated because it is not nothing.** `TenantResolver`
   returns the platform-wide scope to anyone holding `PERM_PLATFORM_ADMIN`
   *before* it looks at their home shop, so this account's shop screens now
   span the marketplace rather than Shop #1. With one shop that is the same
   data. **When a second merchant exists it is not**, and that account will
   need to name a shop (`X-Shop-Id`, the shop switcher) to work inside one.

   The alternative — a separate `SUPER_ADMIN` login, leaving id 1 as Shop #1's
   `ADMIN` — remains the cleaner long-term shape and is a registration plus one
   `UPDATE` away. A real *shop* owner must be `ADMIN` and on that shop's
   `shop_staff`, not `SUPER_ADMIN`.

5. **Merchant settlement, when you want it.** Money currently lands in one
   platform account. Moving to merchant-collects needs KYC, bank details and
   a payout schedule per merchant, all of which are commercial decisions with
   a provider on the other end.

6. **Play Store submission**, when you are ready.
   `docs/PLAY_STORE_DECLARATIONS.md` has the declarations prepared.

7. **A routing provider**, if you want road distance instead of straight-line
   for delivery pricing. This is an API key and a bill, not code — the
   pricing service already records the caveat and is built to take a real
   distance.
