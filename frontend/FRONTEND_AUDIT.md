# Frontend Engineering Audit

Conducted by directly reading and cross-checking the actual source (109
Dart files, excluding generated code), not a surface pass. Every finding
below was verified against real file contents - line numbers and exact
snippets are cited so you can check any of them yourself.

## Overall assessment

**Genuinely solid.** This is the cleanest audit result of anything reviewed
in this project so far - most categories below turned up nothing, which is
a real finding in itself, not a skipped check. The two real gaps are both
already tracked elsewhere (app icon, test coverage) rather than newly
discovered here.

## Error handling - checked every silent catch block, not just some

11 `catch (_)` blocks exist across the codebase. All 11 were individually
read in context:

- 2 in `auth_repository.dart` (`logout`, `logoutAllDevices`) - deliberately
  best-effort: local session state clears regardless of whether the
  server-side call succeeds, documented inline.
- 6 are `_formatDate`/`_formatTime` helpers (admin audit log, invoice
  screen, order history, order detail, notifications) - fall back to
  showing the raw ISO string if parsing fails. Standard, safe defensive
  pattern; worst case is a slightly ugly date, never a crash or data loss.
- 1 in `admin_product_form_screen.dart` - a documented non-fatal display
  refresh after a variant save that already succeeded/failed separately.
- 2 in `notifications_screen.dart` - mark-as-read/mark-all-read failures;
  worst case the unread badge is stale until the next successful load.
- 1 in `api_client.dart`'s token refresh - already reviewed when this file
  was first built; correctly returns `null` on failure, which correctly
  triggers `onSessionExpired`.

**Verdict: no error-swallowing bugs.** Every instance is either genuinely
low-stakes or explicitly documented. This is a real contrast to the
backend, where earlier audit passes in this project found actual
error-swallowing bugs worth fixing - the frontend doesn't have that problem.

## Memory leaks - checked every disposable controller

18 files create `TextEditingController`/`FocusNode`/etc. Two categories:

- **Local, dialog-scoped controllers** (the majority): created inside a
  `showDialog` callback function, not stored as instance fields. These
  don't need explicit disposal - they're garbage collected when the
  function returns after the dialog closes. This is the standard Flutter
  pattern for simple dialogs (Flutter's own docs use the same approach).
- **Instance-field controllers on StatefulWidgets**: checked every one
  against its `dispose()` method. `admin_customers_screen.dart` was the
  most complex case (3 separate State classes in one file, 5 total
  controller fields) - verified all 5 are disposed across the 2 classes
  that own them; the third class has no controllers, correctly has no
  `dispose()` override.

**Verdict: no leaks found.**

## Security - token storage

`token_storage.dart` wraps `flutter_secure_storage` correctly (Keychain on
iOS, Keystore on Android) for both access and refresh tokens. No plaintext
fallback, no `SharedPreferences` anywhere near a credential, no hardcoded
keys. Clean.

## Auth routing

`app_router.dart`'s redirect logic handles all four states correctly:
unauthenticated-on-protected-route → `/login`; authenticated-on-auth-route
→ `/`; the transient "still checking stored session" state on cold start
→ no redirect (avoids a flash-then-redirect UX bug); everything else →
no-op. The Riverpod-to-`refreshListenable` bridge is the correct pattern
for making the router react instantly to logout/session-expiry.

## Code hygiene

- Zero `print()` statements anywhere in `lib/`.
- Zero `TODO`/`FIXME`/`HACK` markers anywhere in `lib/`.
- `analysis_options.yaml` extends `flutter_lints` plus
  `prefer_const_constructors` and `prefer_final_locals` - a reasonable,
  not-overly-loose baseline.

## Real gaps (not new - both already tracked, restated here for completeness)

1. **Test coverage is thin**: 6 test files for 109 source files, and
   they're concentrated in one area (models/repositories for admin
   inventory, orders, cart, products, wishlist) - none of the 18
   controller-heavy screens, the router's redirect logic, or
   `api_client.dart`'s token-refresh/retry logic have any test coverage.
   The parts of this app most likely to have a subtle bug (concurrent
   401-refresh handling, the auth redirect matrix above) are exactly the
   parts with zero tests today. Not a blocker for Play Store submission,
   but worth budgeting time for before this app handles real customer
   traffic at scale.
2. **App icon is still the placeholder vector** (green diamond, plain bag
   shape) - see `PLAY_STORE_CHECKLIST.md` item 3, and
   `assets/icon/README.md` for the one-command fix once you have a real
   logo image.

## What this audit did NOT cover

- Could not run `flutter analyze`, `flutter test`, or build the app -
  no Flutter/Dart SDK available in this environment. Everything above is
  verified by direct source reading, not static-analyzer or test-runner
  output. Run `flutter analyze` and `flutter test` yourself before
  submission for a second, tool-based pass this audit couldn't do.
- Did not audit `android/`, `web/`, or CI YAML files line-by-line beyond
  what was already reviewed during earlier hosting migrations and account
  deletion work earlier in this project.
- Did not review UI/UX, accessibility (screen reader labels, contrast
  ratios), or visual design - this was a code-correctness audit, not a
  design review.
