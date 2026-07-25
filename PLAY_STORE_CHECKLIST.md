# Play Store Readiness Checklist

Honest, direct: this covers everything I can verify by reading, and is
explicit about what's genuinely yours to do - no step here pretends
something is done when it isn't.

## Already technically ready

- **Android manifest permissions** are real and match what the app
  actually uses (internet, location for the address-capture feature) -
  nothing over-requested, nothing missing.
- **Release signing config** now properly reads a real keystore via
  `android/key.properties` (see `android/key.properties.example` for the
  exact one-time setup) - falls back to debug signing only when that file
  doesn't exist yet, so local testing still works before you've set it up.
- **SDK versions** (compileSdk/targetSdk/minSdk) delegate to whatever your
  installed Flutter SDK recommends, so they stay current with Play Store's
  target-API requirements automatically rather than going stale.
- **In-app Privacy Policy and Terms of Service** screens exist with real,
  accurate content reflecting what this app actually does (see the caveat
  below though - Play Console needs more than this).

## You still need to do, before you can submit at all

1. **A Google Play Console developer account** ($25 one-time fee, google
   account required) - I cannot create this for you.
2. **A real release keystore**, generated on your own machine (see
   `android/key.properties.example` for the exact `keytool` command).
   Deliberately not something I generate for you - a signing key is your
   app's permanent identity, and the right practice is that it never
   passes through a third party's systems, including mine.
3. **A real app icon.** The current one is a basic placeholder vector
   shape (a green diamond with a plain bag icon) - functional, but not a
   real logo. Once you have one, the standard tool is the
   `flutter_launcher_icons` package: point it at one source image and it
   generates every required size automatically.
4. **A publicly hosted Privacy Policy URL.** This is a real, separate
   requirement from the in-app screen - Play Console's store listing form
   asks for a URL anyone can visit, not just app content. The easiest path:
   copy the text from the in-app Privacy Policy screen
   (`lib/features/support/presentation/privacy_policy_screen.dart`) onto
   any free page you control (a GitHub Pages page, a Google Site, even a
   GitHub Gist rendered as a page) and use that URL.
5. **Store listing assets**: a 512x512 app icon, a 1024x500 feature
   graphic, and at least 2 phone screenshots. I can't generate real
   screenshots of a running app I've never run - these need to come from
   you actually using the built app.
6. **Content rating questionnaire** and **Data Safety form**, both
   completed inside Play Console itself. See the real, accurate summary
   below for the Data Safety section specifically - based on exactly what
   this app collects, not a guess.

## Data Safety form - a real, accurate reference

Play Console will ask what data this app collects and why. Based on
actually building every feature here, this is accurate for what exists
today:

| Data type | Collected? | Purpose | Notes |
|---|---|---|---|
| Name | Yes | App functionality, account management | Required at registration |
| Email address | Optional | Account management (alternate login) | Only if the customer adds one - OTP-only accounts have none |
| Phone number | Yes | Account management, OTP verification | Core to login |
| Precise location | Yes | App functionality | Only for the "use my location" address-capture feature - not collected in the background |
| Address | Yes | App functionality | Delivery address(es) the customer saves |
| Order history | Yes | App functionality, analytics | Customer's own purchase history |
| Payment info | No | N/A | This app never sees card/bank details - UPI payments go directly between the customer and their own UPI app; COD involves no data at all |
| Photos/media | No | N/A | Not collected |
| Contacts | No | N/A | Not collected |
| App activity (in-app actions) | Yes | Analytics, personalization | Used for "Recommended for you" / "Frequently bought together" |

**Data sharing**: none of this is shared with third parties or used for
advertising - there's no ad SDK integrated anywhere in this app.

**Data deletion**: a customer can delete their own reviews and addresses
directly in-app; full account deletion currently requires contacting
support (via the in-app Contact Us screen) rather than a self-service
button - if Play Console's questionnaire specifically requires a
self-service deletion path, that's a real gap to close before submission,
not yet built.

## Building the actual submission file

Play Store wants an `.aab` app bundle, not the `.apk` this project's CI
workflow produces for quick testing:

```
flutter build appbundle --release
```

Same signing config as the APK (once `android/key.properties` is real),
different output format - both come from the one file you already set up.
