# Merchant authentication, activation and multi-shop: what was built and what is still owed

This is the engineering account of the transformation asked for in Parts 1-4:
one merchant owning one or many shops, a merchant lifecycle the platform can
actually enforce, a first sign-in that needs more than a password, and Super
Admin screens that do not offer buttons the server will refuse.

It is organised by what a reader needs to decide:

- **BLOCKING** - must be resolved before this is trusted with real merchants
- **NON-BLOCKING** - real, recorded, not in the way
- **USER ACTION REQUIRED** - things no test can do, needing a person, a phone,
  or a business decision

Every claim below is either a file you can read or a test you can run. Where
something was proved by deliberately breaking it, the mutation and the test
that caught it are named.

---

## BLOCKING — resolved in this work

### 1. Every account in production resolved to Shop #1

`platform.mode` is set **nowhere in this repository** - not
`application.properties`, not `application-prod.properties`, not the compose
file, not the deploy workflow. Production therefore runs the default,
`SINGLE_SHOP`, and in that mode `TenantResolver.resolve()` returned the first
shop on its first line, before looking at membership at all.

With one shop that is correct: Shop #1 is the only answer there is. With two it
is a cross-tenant read **and write**. The owner of a freshly-created shop asking
for their own profile received `{"id":1,"code":"SHOP-1"}` — proved with a
failing test, not argued.

The platform owner attempted to open a second shop about an hour before this was
found. Had it succeeded and been given an owner login, that owner would have
been reading and writing GP Store's orders, catalogue, customers and takings,
with nothing in any log showing a boundary had been crossed.

**Fixed narrowly, not by flipping the mode.** Changing `platform.mode` is a
production decision with its own consequences for customer browsing, pinned by
`SingleShopBrowseIsUnchangedTest`. What is wrong is smaller and true in every
mode: an account that belongs to a shop belongs to *that* shop. Only a
credential with no shop of its own - a shopper, an account on nobody's roster -
falls back to the first, which is what keeps every existing account working.

- `TenantResolver.shopThisCredentialBelongsTo()`
- `SecondMerchantLandsInTheirOwnShopTest`
- Mutation: restore the old `resolve()` line → that test fails.

### 2. Suspending a merchant did not stop the merchant

Suspension already cascaded to shops and already stopped customers ordering. It
never stopped the merchant: `isOperable` refuses only `REMOVED` and `REJECTED`,
so the owner of a suspended business kept full write access to prices,
catalogue, orders and payouts while the platform believed it had stopped them.

A suspended shop is now **read-only, not locked**, and each exemption is
deliberate:

- the appeal (`POST /api/shop/governance/actions/{id}/appeal`) lives behind the
  same gate — suspending a business and removing its only way to ask why is a
  dead end, not enforcement;
- a rider finishes deliveries already paid for — the suspension is against the
  merchant, not the shopper waiting at the door;
- the platform stays exempt, because managing suspended shops is what
  suspension is for.

Enforced in `TenantContextFilter` because it is the only chokepoint every scoped
request already passes. A rule applied controller-by-controller is missing from
whichever controller is written next.

- `ShopOperationGate`, `TenantContextFilter.mayProceed`
- `SuspendedMerchantIsReadOnlyTest` (5 tests)
- Mutation: remove the write guard → that test fails.

### 3. A merchant onboarded in one screen could not have signed in

`POST /api/platform/onboard` mints a one-time password **and** a fifteen-character
activation code, and the first-login check demands both. The Flutter model for
that response carried only the password, so the console showed the platform
owner one of the two secrets and silently dropped the other.

Every business opened through that dialog would have been handed credentials
that cannot get in — and the recovery ("New activation code") is a button the
merchant would have been told they could not reach. Nothing about it looked
wrong: the dialog said "Hand these over now", showed a password, and was
correct about everything it displayed.

- `OnboardedMerchant.activationCode`, `showOneTimePassword`
- *onboarding shows the activation code beside the password* — drives the
  repository, the form and the dialog, because the dialog was never the broken
  part.
- Mutation: drop the field from the handover → that test fails.

### 4. A merchant who lost the code was permanently locked out

Found by the full suite, not by design review. A merchant who lost the
activation code before first sign-in, then completed an OTP password reset, was
*still* asked for it, and no route could give them a new one they could reach.

The code guards exactly one window: a temporary password in transit. Someone who
merely saw that password cannot get an OTP to the merchant's own phone; someone
who controls that phone can reset regardless. The rule refused exactly one
person — the honest merchant. A completed reset now claims the account.

`AOneTimePasswordBuysOneRouteTest` was updated to pass the code rather than to
expect less; **no assertion was removed**.

---

## NON-BLOCKING — built, recorded, working

### 5. Activation codes

Fifteen characters, `SecureRandom`, alphabet without `l/1/I/O/0`, derived from
nothing — not the merchant id, email, phone or shop, because anything derived
from those is guessable by whoever knows them.

| requirement | how |
|---|---|
| §23 unique, verified server-side | UNIQUE index on the fingerprint, regenerate on collision |
| §24 never plaintext | SHA-256 stored; no route returns it, even to Super Admin |
| §26 first login = email + password + code | password checked **first**, so a leaked code cannot probe its own validity |
| §28 not a third password | `claimedAt` stamped on the claiming login |
| §30 reissue kills the old one | fingerprint overwritten — two live codes leave a leaked one working |
| §48 audit without secrets | a test asserts neither code appears in any audit entry |

**SHA-256 and not bcrypt, on purpose.** bcrypt is slow to protect secrets
*people* chose; `gupta123` has around 20 bits and has to be made expensive to
guess. This has roughly 87 bits, so slowness buys nothing — and costs the
indexable column that makes §23's uniqueness *enforced* rather than hoped for.
Written into `ActivationCodes` so nobody "upgrades" it and silently loses the
index.

`ActivationCodeLoginTest` (13 tests).

### 6. PAUSED, as a state distinct from SUSPENDED

A pause is a shutter down for Diwali. A suspension is an accusation, and that
record is what an appeal is argued from. `PAUSED` can be lifted or escalated to
`SUSPENDED`; `SUSPENDED` cannot be quietly downgraded to `PAUSED`.

`V67` widens the Hibernate-generated `merchants_status_check`, which listed the
values that existed when the table was built. Strictly widening — nothing
deleted, nothing backfilled.

### 7. `M-000001` / `S-000001`

Derived, never stored, never parsed. An identifier nothing reads back cannot be
forged into a permission (§3). `shops.code` is untouched (§43). Padding widens
rather than truncates, so the millionth shop is not a collision with the first.

`PublicIdsTest` (4 tests).

### 8. The business is not the shop

`GET /api/shop/merchant` refuses anybody who is not the owner — **including a
MANAGER of that very shop**, because a permission set is something a shop can
hand out and ownership is not.

`MerchantLevelDataIsNotShopDataTest` (6 tests), which also tries the two
console routes the Super Admin screens added — merchant detail, and reissue
activation code — as a shop ADMIN. The second is the one that matters: a
reissue overwrites the stored fingerprint, so whoever calls it holds the only
live code for that account, and the test aims it at a *different* merchant's
owner.

Mutation: widen `/api/platform/**` from `hasAuthority(PLATFORM_ADMIN)` to
`authenticated()` → both answer **200**, and a merchant could have minted
themselves a way into a competitor's account.

### 9. MY SHOPS and the one-tap switcher

With one shop the switcher renders **nothing at all**, so the single-shop app is
untouched (§59). The shop name is on screen whenever there are two or more
(§64) — the shell previously showed no shop name anywhere, so a merchant who
believes they are in GP Store and is actually in Deepak Hardware changes the
wrong prices and finds out when a customer complains.

Non-operable shops are listed but disabled rather than hidden: a merchant whose
shop was suspended needs to see that it exists.

`OneMerchantManyShopsTest` (6 tests), `shop_switcher_bar_test`.

### 10. Cache isolation on switch, with a guard test

The obvious cascade is unavailable: `ApiClient` reads the shop per request
because rebuilding it would drop in-flight requests. So the invalidation list is
hand-written — and a hand-written list rots. `ShopSwitchClearsEverythingTest`
reads the provider files and fails *naming* the provider that is not cleared.

Mutation: drop one provider from the list → the test fails naming it.

### 11. Super Admin merchant screens

- Only **legal** transitions are offered. The console used to draw every status
  on every card, so tapping "Approve" on a business in APPLICATION produced a
  refusal that is correct, arrives after the tap, and explains nothing about
  what to do instead.
- `merchant_transitions_offered_test` parses `MerchantTransitions.nextFrom` and
  `MerchantStatus.allowedNext` side by side and fails on any divergence.
- A merchant detail screen showing every shop under the business, read in one
  call keyed by merchant id on the server.
- The suspension cascade is stated **before** the button: "Pausing or suspending
  this business stops all 3 of its shops."
- "Add shop" from the business, with no merchant picker — picking one out of a
  list is where a shop gets attached to the wrong business.
- Shop count on the list card, omitted rather than zeroed when not yet known.

### 12. What the existing test suite could not see

Dropping the membership check in `TenantResolver.select` leaves **all 16**
`CrossTenantShopCatalogTest` tests green. They establish scope with
`TenantContext.runWithin` rather than *asking* for it through a header. The new
tests fail.

The existing suite was proving the filter. The new tests prove the door, and
`X-Shop-Id` is the only lever a client actually has.

### 13. A client-supplied shop id is ignored, never fatal

An early version of the hardening made a contradictory `X-Shop-Id` throw. Eight
tests went red across `CrossTenantShopCatalogTest`,
`CrossTenantDataIsolationTest`, `MarketplaceIdentityTest` and
`ShopScopeIsNotOptionalTest` — all of which deliberately smuggle a body naming
another shop and assert that the stamp **silently rewrites** it.

That is the contract, and it is the safer one: a request that would have leaked
is written to the caller's own shop instead of returning an error that confirms
the other shop exists. The change was reverted to a WARN log. **No isolation
assertion was edited.**

### 14. A switcher sheet that overflowed

A widget test caught the shop-switcher sheet overflowing at 289 logical pixels
with three shops. §45 puts no cap on shop count, so that breaks on a short phone
at the fourth or fifth. It scrolls now.

### 15. Production read-only diagnostics

`deploy/production/who-runs-the-shop.sql` was extended with merchants, shops,
id-sequence-versus-max-id, per-shop singleton rows and unique indexes. Every
statement is a SELECT and it passes the workflow's own read-only guard.

`scripts/verify/smoke_api.sh` gained exact-403 probes for
`POST /api/platform/onboard` and `POST /api/platform/staff/{id}/reissue-activation-code`,
so an unauthenticated caller reaching either is a smoke failure rather than a
discovery.

### 16. Migrations

`V67` (PAUSED) and `V68` (activation code columns and partial unique index).
Both applied from an empty database through the full Flyway replay. All new
columns are nullable and nothing was backfilled, so an existing merchant is
unaffected until a code is issued.

### 17. Every tap, through one place

Thirty raw `HapticFeedback.*` calls had grown back across eighteen files. Each
one bypassed `AppHaptics` — the wrapper that exists because "haptics were
already called in about twenty places, each picking an intensity by hand". A
raw call ignores `AppHaptics.enabled` (so it reaches the platform channel
during widget tests), is invisible to `AppHaptics.callCount` (so the
"one tap produces exactly one haptic" assertion cannot see it, which is exactly
the double-buzz bug that assertion exists to catch), and picks its own
intensity.

All thirty were refactored 1:1 — `selectionClick` → `selection()`,
`lightImpact` → `action()`, `mediumImpact` → `heavy()` — so nothing about how
the app feels changed. About twenty genuinely-missing haptics were added at the
same time, on controls that had none: Brands see-all, the horizontal product
rails, category browse, login, register, change password, write review, request
return, add address, order detail, order group, voice search, brand products,
product detail, profile avatar, address list, the variant picker, and Apply
coupon at checkout.

Then a guard: `test/core/util/one_place_for_haptics_test.dart` walks `lib/` and
fails naming file and line on any `HapticFeedback.` outside the wrapper, with a
second test proving the pattern can still see a bypass.

A full pass over every interactive callback took the suspect list from 44 to 12
to 9, and each of the nine was read and confirmed a false positive — a
`TextField.onChanged` (which must not buzz per keystroke), a callback forwarded
into a widget that hapticizes it, or a handler that already buzzes inside
itself. Those are recorded rather than "fixed", because adding a second buzz to
any of them would be the defect.

**Mutation:** put a raw `HapticFeedback.mediumImpact()` back in any `lib/` file
→ `nothing calls HapticFeedback directly except AppHaptics` fails and names it.

### 18. Every remote image, through one place

Eight `Image.network` call sites sat outside `GpNetworkImage` — one on
the rider's packing list, seven across the shopkeeper's screens. All eight were
scrolling lists of thumbnails, which is the worst possible place to lose what
that widget holds:

- **no disk cache.** `Image.network` caches in memory for as long as the widget
  lives. Scrolling back up a catalogue re-downloaded every photograph, and
  reopening the screen re-downloaded all of them again.
- **no CDN size.** A 48-pixel row pulled the full original — up to 1600px — over
  a shop's mobile data.
- **no decode cap.** Each tile decoded at the file's resolution rather than the
  screen's, so twenty rows each held a full-resolution bitmap. That is the
  specific thing that makes a scroll stutter on a cheap phone.

**Seven were outside for a cosmetic reason:** `GpNetworkImage` drew its
stand-in in the storefront palette, and those screens are drawn in the admin
one. Two palettes is not a reason for two image pipelines, so the palette
became an argument — `placeholderColor`, `placeholderIconColor`, and a
`placeholder` widget for the one case an icon cannot express (a customer
avatar, where the stand-in is the person's initial). Every customer-app call
site passes none of them and did not change.

**The eighth was a real exception, and converting it broke the release build.**
The rider's packing list stays on `Image.network`. The delivery-worker APK is
built through `tool/with_worker_pubspec.sh`, which swaps in
`pubspec.worker.yaml` — a deliberately short dependency list that leaves out
Firebase, WebView, TTS, BLE, speech, Cashfree *and* `cached_network_image`, so
none of that native code is packaged into an APK a rider installs. Importing
`GpNetworkImage` from a worker screen therefore does not cost a dependency; it
fails the build outright with `Couldn't resolve the package
'cached_network_image'`.

That is not hypothetical. It was pushed, it merged, and it turned `main` red.
842 Flutter tests passed, `flutter analyze` was clean and the customer APK
built — because all of those resolve against the full `pubspec.yaml`. The
worker build was the only thing in the repository using the short one, and
nothing checked it until Gradle did, three minutes in, after the merge.

Guards, both mutation-checked:

- `test/core/images/one_place_for_images_test.dart` — `Image.network`,
  `NetworkImage` and the underlying `CachedNetworkImage` outside
  `gp_network_image.dart` fail it by file and line. The worker screen is
  exempt, and **the exemption expires by itself**: a third test fails the day
  `cached_network_image` appears in `pubspec.worker.yaml`, so the entry cannot
  outlive its reason.
- `test/core/images/worker_apk_stays_slim_test.dart` — walks the real import
  graph from `lib/worker_main.dart` and fails on any `package:` import absent
  from `pubspec.worker.yaml`, printing the chain that reached it. It guards
  itself too, asserting the walk still reaches the worker screens and still
  excludes `gp_network_image.dart`, so a broken walk cannot pass by seeing
  nothing.

**Mutation:** re-add the `GpNetworkImage` import to `worker_order_screen.dart`
→ the slim test fails in three seconds naming `package:cached_network_image`
and the chain `worker_order_screen.dart → gp_network_image.dart`. That is the
same fact CI took three minutes and a red `main` to report.

### 19. What the lag audit could and could not establish

Structural causes of jank, all checkable from source and all checked:

- **`saveLayer`: none, app-wide.** No `Opacity` widget, no `BackdropFilter`, no
  `ImageFiltered`, no `Clip.antiAliasWithSaveLayer` anywhere in `lib/`. The
  theme's "shadows, transforms and alpha only" rule is genuinely held rather
  than merely written down.
- **`shrinkWrap: true`: seven sites, all bounded.** Eight fixed category cells,
  one product's variants, a `Flexible` shop switcher, a capped empty-state, and
  three admin screens. None wraps an unbounded API list.
- **Non-lazy `for (final … in …)` spreads: all bounded.** Shop comparison is
  capped server-side at `ShopOffers.MAX_SHOPS_COMPARED = 20`; cart groups and
  order lines are bounded by one basket; search matches are capped at three; the
  returns one is arithmetic, not widget building.
- **Provider families: every key is an `int` or a record.** Both have value
  equality, so none of them re-fetches on every rebuild — the failure mode that
  looks like a slow screen and is actually an infinite request loop.
- **Timers and controllers: all disposed.** Nine files create an
  `AnimationController` or a `Timer.periodic`; eight have `dispose()`, and the
  ninth (`AdminOrderSoundWatcher`) is released through `ref.onDispose`.
- **Home rebuild scope** is already pinned by `home_rebuild_scope_test`: each
  section sits in its own `Consumer`, so one section's data arriving does not
  rebuild the screen.

**What this does not establish.** None of it is a frame time. Measuring jank
means a profile build on a real phone, and the honest statement is that nobody
has run one. Structural causes being absent makes stutter unlikely; it does not
make the app measured. The same caveat as everywhere else in this document
applies with particular force here — the most consequential defect found in this
work (merchants handed credentials that could not sign in) passed every
automated check there was.

---

## USER ACTION REQUIRED

These need a person. None can be automated honestly, and inventing a result for
any of them would be worse than leaving it open.

### 17. Physical-device and real-merchant testing

- **A real two-merchant test.** Two genuine businesses, two owner logins, each
  confirming they see only their own orders, catalogue and takings. The
  automated tests prove the server refuses the crossing; only real accounts
  prove the whole journey.
- **A real multi-shop test.** One merchant with two or more shops, switching
  between them on a phone, confirming the shop name on screen always matches the
  data below it.
- **A real first sign-in.** Email, one-time password and activation code typed
  on a device, by somebody who was not watching this being built.

The activation-code lockout in item 3 is precisely the class of defect that only
this catches: every automated test passed, and the credentials handed over could
not have signed in.

### 18. Open business decisions and outstanding data

- **GUPT SAREE** is still in `APPLICATION`. It needs *Send for review* then
  *Approve* before it can hold a shop. That is a review decision, not a code
  change.
- **A contact email with a doubled `d`** was recorded during onboarding, and
  account 993 may carry the same typo. Correcting a real merchant's contact
  details is a data decision for the platform owner; it is not being guessed at
  here.
- **`platform.mode` is still unset**, so production still runs `SINGLE_SHOP`.
  The cross-tenant defect is fixed independently of the mode, so this is not
  urgent — but it is a decision that should be made deliberately rather than
  inherited from a default, and flipping it changes customer browsing behaviour
  that `SingleShopBrowseIsUnchangedTest` currently pins.
- **A read-only production-log workflow** was proposed and blocked by the
  sandbox as "Production Reads". Whether to add it is the platform owner's call.

---

## How to re-run what this claims

```bash
# backend: wipe schema, full Flyway replay, whole suite
# last run: 1908 tests, 0 failures, 0 errors, 1 skipped
cd backend && mvn clean verify

# flutter
# last run: 845 tests passing, analyze at 40 infos, no warnings, no errors
cd frontend && flutter analyze && flutter test
```

Mutation testing is manual and deliberate: remove the named protection, run the
named test, confirm it fails, restore. The pairs are listed against each item
above.
