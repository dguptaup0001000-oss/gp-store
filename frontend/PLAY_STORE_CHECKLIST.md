# Play Store Readiness Checklist

Honest, direct: this covers everything I can verify by reading, and is
explicit about what's genuinely yours to do - no step here pretends
something is done when it isn't.

## Already technically ready

- **Android manifest permissions** match each APK: the customer app
  (`com.gpstore.app`) strips CAMERA from the merged manifest (worker QR
  scan lives in a separate APK). The worker app (`com.gpstore.worker`)
  uses camera + foreground location only. Neither app requests
  `ACCESS_BACKGROUND_LOCATION`.
- **Release signing** fails the Gradle build unless `android/key.properties`
  exists. Sideload CI may set `ALLOW_DEBUG_RELEASE_SIGNING=1`; those APKs
  must not be uploaded to Play.
- **In-app Privacy Policy** and a public URL
  (`https://dguptaup0001000-oss.github.io/gp-store/privacy-policy.html`)
  describe what the software actually collects. Account deletion requires
  the current password.
- **Self-service account deletion**, both required paths: an in-app
  "Delete Account" option (Profile screen, password step-up) and a public
  web page (`web/account-deletion.html`).

## You still need to do, before you can submit at all

1. **A Google Play Console developer account** ($25 one-time fee, google
   account required) - I cannot create this for you.
2. **A real release keystore**, generated on your own machine (see
   `android/key.properties.example` for the exact `keytool` command).
   Deliberately not something I generate for you - a signing key is your
   app's permanent identity, and the right practice is that it never
   passes through a third party's systems, including mine.
3. **A 512×512 store listing icon** (Play Console still wants this even
   though the in-app launcher uses a GP bag mark).
4. **Paste the public Privacy Policy URL into Play Console:**
   `https://dguptaup0001000-oss.github.io/gp-store/privacy-policy.html`
   (also `web/privacy-policy.html` in this repo; GitHub Pages publishes it).
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
| Precise location | Yes | App functionality | Customer: one-shot GPS when adding an address. Delivery partners using this same app: foreground GPS while the delivery dashboard is open. Not collected in the background. |
| Address | Yes | App functionality | Delivery address(es) the customer saves |
| Order history | Yes | App functionality, analytics | Customer's own purchase history |
| Payment info | No | N/A | This app never sees card/bank details - UPI payments go directly between the customer and their own UPI app; COD involves no data at all |
| Photos/media | Shop catalogue only | App functionality | Administrators upload product photos to Cloudinary. The app does not collect customer personal photos. |
| Contacts | No | N/A | Not collected |
| App activity (in-app actions) | Yes | Analytics, personalization | Used for "Recommended for you" / "Frequently bought together" |

**Data sharing**: none of this is shared with third parties or used for
advertising - there's no ad SDK integrated anywhere in this app.

**Data deletion**: a customer can delete their own reviews and addresses
directly in-app, and can now delete their entire account and personal data
self-service - either in-app (Profile → Delete Account) or via the public
web page at `<your-domain>/account-deletion.html`, with no need to contact
support first. When filling out Play Console's Data Safety form, select
"Users can request that their data be deleted" and enter that web page's
full URL (e.g. `https://<you>.github.io/<repo>/account-deletion.html`).

## Building the actual submission file

Play Store wants an `.aab` app bundle, not the `.apk` this project's CI
workflow produces for quick testing:

```
flutter build appbundle --release
```

Same signing config as the APK (once `android/key.properties` is real),
different output format - both come from the one file you already set up.
