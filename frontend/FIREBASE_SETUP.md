# Push notifications - one-time Firebase setup

Both the backend and Flutter app now have all the CODE for real push
notifications (order status updates, new delivery assignments). What's
missing is a real Firebase project - that requires logging into a Google
account and clicking through Firebase's console, which genuinely can't be
done from here. This is that missing piece, step by step.

Until you do this, the app and backend both run completely normally - push
notifications just silently don't fire (see `firebase.push-enabled` in the
backend and the try/catch around `Firebase.initializeApp()` in
`main.dart`). Nothing breaks by skipping this for now.

## 1. Create the Firebase project

1. Go to https://console.firebase.google.com
2. **Add project** → name it (e.g. "GP-Store") → you can skip Google
   Analytics, not needed for push notifications.

## 2. Register your Android app

1. In the new project, click the Android icon ("Add app").
2. **Register two Android apps** in the same Firebase project:
   - Customer package name: `in.gpstore.customer`
   - Admin package name: `in.gpstore.admin`
   Both must match `android/app/build.gradle` productFlavors exactly.
   Download one `google-services.json` that lists **both** clients (Firebase
   Console → Project settings → Your apps → the JSON includes every Android
   app in the project). Until that secret is updated, CI clones the existing
   `com.gpstore.app` client so Gradle can match the new applicationIds;
   push/Crashlytics for those IDs are not fully registered until you add
   the apps in Firebase. The Crashlytics Gradle plugin (mapping upload)
   is applied only when `CRASHLYTICS_MAPPING_UPLOAD=1`; a cloned app id
   returns HTTP 400 and fails the release APK.
3. Download the `google-services.json` file it offers you.
4. Put that file at `android/app/google-services.json` in this project
   (same folder as `android/app/build.gradle`). This file is
   project-specific and already gitignored - never commit it, it's tied to
   your Firebase project.
5. For GitHub Actions Play-signed APKs/AABs (`ANDROID_KEYSTORE_*` set), also
   store the same file as repo secret `GOOGLE_SERVICES_JSON_BASE64`
   (`base64 -w0 android/app/google-services.json`). Play-named artifacts fail
   the job if this secret is missing so a placeholder cannot ship to Play.
   Sideload/debug-signed CI copies `google-services.placeholder.json`
   (not a real Firebase project; push and Crashlytics stay off).
6. Skip the rest of Firebase's setup wizard (SDK snippets, etc.) - the
   `firebase_core`/`firebase_messaging` packages already handle that; you
   only needed the JSON file.

The **delivery worker** APK uses a different `applicationId`:
`com.gpstore.worker`. Worker slim builds do not apply the Google Services
plugin, so they do not need a second `google-services.json` entry today.
If you later add Firebase to the worker app, register a second Android
app in the same Firebase project with package `com.gpstore.worker` and
include both clients in `google-services.json`. Do not reuse
`in.gpstore.customer` or `in.gpstore.admin` for the worker APK.

## 3. Get a service account key for the backend

The backend needs its own credential (separate from the Android app's) to
actually SEND pushes via the Firebase Admin SDK:

1. Firebase Console → the gear icon → **Project settings** → **Service
   accounts** tab.
2. Click **Generate new private key** → confirm → downloads a JSON file
   (something like `gp-store-firebase-adminsdk-xxxxx.json`).
3. This file must become the `FIREBASE_CREDENTIALS_BASE64` env var on
   the VPS (`/opt/gpstore/env.production`) — base64-encode the WHOLE file content:
   - **Termux/Linux/Mac**: `base64 -w0 path/to/that-file.json`
   - **Windows PowerShell**: `[Convert]::ToBase64String([IO.File]::ReadAllBytes("path\to\that-file.json"))`
4. Copy the long output string, paste it as the value of
   `FIREBASE_CREDENTIALS_BASE64` in `/opt/gpstore/env.production`.
5. Also add `FIREBASE_PUSH_ENABLED=true` in that same env file
   (it defaults to `false` - see `application.properties`).
6. Save → `sudo systemctl restart gpstore-backend`. Check the logs for `Firebase Admin SDK
   initialized - push notifications are live.` to confirm it worked. If you
   see a warning instead, the base64 value is probably malformed (extra
   whitespace/newline from copy-paste is the usual cause).

**Never commit either JSON file to git** - both are real credentials.

## 4. Rebuild the app

`google-services.json` is read at BUILD time by the Gradle plugin
(`android/app/build.gradle`), so:

```
flutter clean
flutter pub get
flutter run --dart-define=API_BASE_URL=https://api.gpstore.co.in/v1
```

If `google-services.json` is missing, the build fails immediately with a
clear "File google-services.json is missing" error - that's intentional,
so a missing setup step shows up as a build failure, not a silently broken
feature.

## 5. Test it end to end

1. Log in as a customer on a real device/emulator (push doesn't work on
   Chrome/web builds without extra web-specific Firebase setup - test on
   Android for now).
2. Grant the notification permission prompt when it appears.
3. As an admin, change that customer's order status (e.g. mark it
   `OUT_FOR_DELIVERY`) via the admin order screen or API.
4. A real system notification should appear on the device within a few
   seconds. Tapping it should open the app directly to that order's detail
   screen.
5. For the delivery-partner side: assign an order to a partner (via
   `/api/deliveries/auto-assign` or the admin screen) while logged in as
   that partner on a device - they should get a "New Delivery Assigned"
   push.

## Known limitations of this first version

- **One device token per account.** If the same person is logged into two
  phones, only the most recently opened one receives push. Multi-device
  support would need a separate token table - not built, since nobody
  actually needs it yet at this scale.
- **Foreground notifications show as an in-app banner (SnackBar), not a
  system tray notification.** This is standard FCM/Android behavior - the
  OS only auto-shows the system notification when the app is backgrounded
  or closed. Showing a real heads-up notification while the app is open too
  would need the `flutter_local_notifications` package on top of this -
  a reasonable next addition, not included here to keep this round's scope
  contained.
- **iOS is not set up.** This app doesn't have an `ios/` folder generated
  yet at all (per `PLAY_STORE_CHECKLIST.md`, iOS was never in scope for the
  initial launch). Firebase's iOS setup (APNs certificates, a
  `GoogleService-Info.plist`) is a separate step for whenever that becomes
  relevant.
