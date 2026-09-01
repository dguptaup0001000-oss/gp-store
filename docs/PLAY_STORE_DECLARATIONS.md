# Play Console declarations for the three GP-STORE apps

Written so nobody has to reverse-engineer the answers from the manifests under
time pressure the day a release is blocked. **Every line here is taken from
what the code actually does.** If a declaration and the code ever disagree,
the code is the thing to change first — a false declaration is grounds for
removal, and it is checked against the APK Google receives, not against intent.

The permission sets below are enforced on every build by
`frontend/tool/verify_apk_release.py`, which reads the built APK after manifest
merging, and by `frontend/tool/assert_worker_location_manifest.py`, which reads
the source. If you change a declaration here, one of those two will tell you
whether the app agrees.

## The three apps

| App | Package | Entrypoint |
|---|---|---|
| Customer | `in.gpstore.customer` | `lib/main.dart` |
| Admin | `in.gpstore.admin` | `lib/admin_main.dart` |
| Delivery Worker | `com.gpstore.worker` | `lib/worker_main.dart` |

Three separate applicationIds on purpose: sharing one would make installing
either replace the other.

---

## 1. Foreground service — Worker app ONLY

**This is the declaration that blocks a release if it is missing.** The worker
app declares `FOREGROUND_SERVICE_LOCATION`, and Google requires a Foreground
Service declaration for it in Play Console → App content.

| Field | Answer |
|---|---|
| Which app | Delivery Worker (`com.gpstore.worker`) only |
| Foreground service type | **Location** |
| What the app does with it | Shares the rider's position with the shop while they have an assigned delivery out, so the shop can tell a waiting customer where their order is |
| Why a foreground service is required | A rider cannot hold the app open on screen while riding. Without a foreground service Android stops location updates the moment the screen locks, which is exactly when the position matters |
| Is there a less invasive alternative | No. Periodic background work is not permitted for continuous location, and `ACCESS_BACKGROUND_LOCATION` would be strictly more invasive — see below |
| When does it start | Only when the signed-in rider has at least one active assigned delivery |
| When does it stop | Delivery completed, sign-out, account deactivated by the shop, or the screen being destroyed |
| Is it user-visible | Yes — an ongoing, non-dismissable notification for the whole time it runs, reading "Sharing your location / Visible to the shop while you have a delivery out. Stops when you finish." |

The Customer and Admin apps declare **no** foreground service permissions, and
CI fails the build if one is ever merged into them.

## 2. Location

| App | Declared | Answer |
|---|---|---|
| Customer | `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION` | "Use my current location" when adding a delivery address. Foreground only, on that screen |
| Admin | none | Location permissions are explicitly stripped from this flavor |
| Worker | `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION` | Delivery tracking, via the foreground service above |

**`ACCESS_BACKGROUND_LOCATION` is not declared by any of the three apps**, and
is stripped with `tools:node="remove"` in the worker manifest so no merged
plugin manifest can reintroduce it. Answer "no" to background location. The
distinction is real and worth stating in the review notes: a foreground service
follows a rider **while they are working**; background location would follow
them when they are not.

## 3. Camera

| App | Declared | Answer |
|---|---|---|
| Customer | none — stripped from the merged manifest | — |
| Admin | none | — |
| Worker | `CAMERA` | Scanning the QR label on a packed order. Nothing is recorded or uploaded; the frame is decoded on the device |

## 4. Microphone

| App | Declared | Answer |
|---|---|---|
| Customer | `RECORD_AUDIO` | Voice search. Uses the recogniser already on the device — no audio is recorded, stored or uploaded by this app |
| Admin | none — removed from this flavor | — |
| Worker | none | — |

## 5. Bluetooth — Admin app only

`BLUETOOTH_CONNECT`, and `BLUETOOTH_SCAN` with
`android:usesPermissionFlags="neverForLocation"`.

Connecting to a thermal receipt printer the shopkeeper has already paired in
Android's own Bluetooth settings. It is not used to derive location, which is
what the `neverForLocation` flag asserts. Legacy `BLUETOOTH`/`BLUETOOTH_ADMIN`
are capped at `maxSdkVersion="30"`.

## 6. Notifications

`POST_NOTIFICATIONS` is declared by all three apps and requested **in context**,
never on first launch:

- Customer / Admin — when push registration happens, for order updates
- Worker — at the moment location sharing starts, because the foreground
  service's notification is hidden without it on API 33+

## 7. Not declared, deliberately

`NFC` and `DUMP` are stripped with `tools:node="remove"`, and the NFC hardware
feature is marked not-required, so the apps install on devices without it.

---

## Data safety

Answer per app. The backend never returns password hashes, tokens or payment
secrets to a client, and nothing sensitive is written to device logs in release
builds (`appLog` is a no-op outside debug).

| Data type | Collected | Shared | Purpose |
|---|---|---|---|
| Name, email, phone | Yes (all apps) | No | Account, order contact |
| Address, approximate + precise location | Customer: yes | No | Delivery address and eligibility |
| Precise location | Worker: yes, **while a delivery is active only** | Shared with the shop operating the app | Delivery tracking |
| Photos | Customer: profile picture, optional | No | Account personalisation |
| Purchase history | Yes | No | Order history, receipts |
| Payment info | **Not collected by the app.** Card/UPI details are entered in Cashfree's own flow and never touch GP-STORE code | — | — |
| Crash logs, diagnostics | Customer / Admin, via Crashlytics | No | Stability |

Encrypted in transit: **yes** — HTTPS only, cleartext disabled at the manifest
and network-security-config level. Users can request deletion: **yes** —
account deletion exists in the customer app.

## Release facts

| | |
|---|---|
| versionName / versionCode | from `frontend/pubspec.yaml` — currently `1.0.0+2`. **Bump before each upload; Play rejects a reused versionCode** |
| min / target SDK | whatever the pinned Flutter SDK recommends (`flutter.minSdkVersion` / `flutter.targetSdkVersion`) |
| Signing | release keystore from `android/key.properties`, which is **not** in git. Losing it means never updating these listings again — keep an off-machine copy |
| Upload format | App Bundle (`.aab`). CI produces all three |
| Debuggable | false in release; `usesCleartextTraffic=false` outside the debug overlay |

