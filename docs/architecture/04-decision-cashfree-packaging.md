# GP-STORE — decision: the Cashfree SDK is packaged into apps that cannot reach it

**Status:** accepted, deliberate, revisit-on-trigger.
**Scope:** Android packaging only. **No behaviour changed by this document.**

One `pubspec.yaml` builds four apps. `flutter_cashfree_pg_sdk` is declared once and
therefore linked into all four, while only the Customer app can execute a payment
flow. That is known, measured, and accepted for now. This document records why, what
it costs, and what has to be true before it changes.

---

## The debt, stated plainly

The Merchant Admin, Super Admin and Worker APKs each carry the Cashfree mobile SDK's
classes and resources. None of those three apps can reach it: no code path in any of
them imports the checkout service. The SDK sits in the package, registered at startup
and never invoked.

It is dead weight. It is not a vulnerability, and the distinction matters — see
"What this is not" below.

---

## What was measured

Pulled from Maven Central and inspected, rather than estimated:

| Artifact | Download | Expanded | Files | Native `.so` |
|---|---|---|---|---|
| `com.cashfree.pg:api:2.4.0` | 8.9 KB | — | — | 0 |
| `com.cashfree.pg:core:2.4.0` | 1.13 MB | 1.38 MB | 251 | **0** |
| `com.cashfree.pg:ui:2.4.0` | 561 KB | 718 KB | 183 | **0** |

**The SDK ships no native libraries at all.** It is JVM classes and Android
resources. After R8 shrinking and DEX compression the realistic contribution to an
APK is roughly **0.5–1 MB** — about 2–4% of a 25 MB build.

An earlier estimate of "~5 MB" circulated and was wrong. It is not what makes these
APKs 25 MB; the Flutter engine, ICU data, fonts and assets are.

## Which apps reach it

Established by walking the import closure from each entrypoint, not by grepping an
entrypoint — the SDK is four hops down (customer shell → cart → checkout screen →
checkout service), so a shallow check would have said "no" for every app including
the Customer one.

| App | Reaches the SDK |
|---|---|
| Customer | **Yes** |
| Merchant Admin | No |
| Super Admin | No |
| Worker | No |

Enforced by `frontend/test/app_separation_test.dart`. The Customer assertion is the
control: if the walker broke or the closure came back empty, "the customer app
reaches the Cashfree SDK" fails, and the three `isFalse` assertions beside it would
be passing for no reason.

---

## Why it was not removed

Not for want of trying to find a safe way. The blocker is specific.

`GeneratedPluginRegistrant.java` is gitignored and regenerated on every build, and
it registers every declared plugin for every flavor:

```java
try {
  flutterEngine.getPlugins().add(new com.cashfree.flutter_cashfree_pg_sdk.FlutterCashfreePgSdkPlugin());
} catch (Exception e) {
  Log.e(TAG, "Error registering plugin flutter_cashfree_pg_sdk, ...", e);
}
```

The plugin class is declared as:

```java
public class FlutterCashfreePgSdkPlugin implements FlutterPlugin, MethodCallHandler,
        ActivityAware, CFCheckoutResponseCallback, CFSubscriptionResponseCallback
```

The last two are `com.cashfree.pg.core.api.callback` interfaces. **A class cannot be
loaded without the interfaces it implements.** So excluding the SDK for a flavor makes
that `new` throw `NoClassDefFoundError` — which is an `Error`, not an `Exception`, and
the `catch` above does not catch it. The app dies at startup.

A working removal therefore needs all of:

1. the SDK excluded for the `superadmin` and `worker` variants,
2. the plugin wrapper subproject excluded for them too, or D8 fails on a duplicate class,
3. a same-FQN stub `FlutterCashfreePgSdkPlugin` in `android/app/src/superadmin/` and
   `src/worker/` so the regenerated registrant still compiles and links.

That is class-shadowing against a dependency Flutter's plugin loader adds for all
variants inside an `afterEvaluate`. It is fragile by construction and breaks on a
plugin upgrade.

**And it cannot be verified.** There is no Android SDK or emulator in the development
container and none in CI. CI can assert which classes are in an APK; nothing available
to this project can assert that the app still *launches*. The failure mode is Super
Admin and Worker not starting.

Roughly 1 MB on two internal apps does not buy a startup-crash failure mode that
nobody can test for. The decision is to carry the debt visibly instead.

---

## What this is not

**Not a credential exposure.** There is no Cashfree key, app-id or secret anywhere in
the Flutter source. The client receives only a session minted server-side for one
specific order, and `GatewayPaymentOwnershipTest` asserts that another customer can
neither start nor verify one.

**Not a loss of payment visibility for Super Admin.** Payments and refunds reach the
platform console through `/api/platform/control/payments` and `/refunds` — backend
reads behind `PLATFORM_ADMIN`, with nothing to do with the client checkout SDK.
Removing the SDK from that build would never have affected them, and nothing here
weakens them.

**Not a reason to relax any payment test.** The ten server-side properties
(signature verification including stale-timestamp rejection, webhook idempotency and
duplicate delivery, amount verification, order ownership, refund association,
server-side status, client-forged success) stay exactly as they are.

---

## The trigger to revisit

This debt is paid when the Flutter apps are split into independently managed packages
or pubspecs — a Dart workspace, or separate projects per app.

At that point each build declares only the plugins it uses, the whole class of problem
disappears, and **Cashfree must be removed from every app that does not use it**. No
stub, no exclusion trick, no class shadowing: the dependency simply is not declared.

Until then, `app_separation_test.dart` is what keeps the premise honest. If someone
routes a merchant or an operator through checkout, that test fails — which is the
signal that the table above has changed and this document needs rewriting, rather
than a packaging decision quietly resting on something that stopped being true.
