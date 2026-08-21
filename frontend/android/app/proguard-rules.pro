# GP-STORE R8/ProGuard rules.
#
# WHY THIS FILE HAS TO EXIST AT ALL. R8 removes any class it cannot see a
# reference to. Code reached through reflection or from native code has no
# visible reference, so R8 is correct to think it is unused and wrong to
# remove it - the result is a release build that compiles cleanly and then
# crashes at runtime with ClassNotFoundException, on a customer's phone,
# in a build nobody tested because debug builds do not run R8.
#
# Each keep below names something reached that way.

# --- Flutter engine ---------------------------------------------------
# The engine calls into these from C++ via JNI. Nothing in Java references
# them, so without this R8 strips the bridge the whole app runs on.
-keep class io.flutter.app.** { *; }
-keep class io.flutter.plugin.** { *; }
-keep class io.flutter.embedding.** { *; }
-keep class io.flutter.util.** { *; }
-keep class io.flutter.view.** { *; }
-keep class io.flutter.** { *; }
-keep class io.flutter.plugins.** { *; }

# --- Firebase / FCM ---------------------------------------------------
# Messaging instantiates the service and its callbacks reflectively; push
# notifications silently stop arriving if these are renamed.
-keep class com.google.firebase.** { *; }
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.firebase.**
-dontwarn com.google.android.gms.**

# --- Bluetooth thermal printer ---------------------------------------
# Plugin channels are resolved by name at runtime.
-keep class com.example.print_bluetooth_thermal.** { *; }

# --- Text to speech ---------------------------------------------------
# The voice order announcement depends on this; a stripped callback class
# means the shop stops hearing orders while everything else looks fine.
-keep class com.tundralabs.fluttertts.** { *; }

# --- Secure storage ---------------------------------------------------
-keep class com.it_nomads.fluttersecurestorage.** { *; }

# --- Play Core / deferred components ----------------------------------
# R8 FAILED THE FIRST RELEASE BUILD ON THIS, which is the entire reason the
# step exists: nothing here can go wrong in a debug build.
#
# Flutter's embedding ships FlutterPlayStoreSplitApplication and
# PlayStoreDeferredComponentManager, which reference Play Core's
# split-install API. Play Core is NOT a dependency of this app, so R8 sees
# a dozen unresolvable class references and refuses to complete.
#
# Suppressed rather than pulled in, and the distinction matters because a
# blanket -dontwarn is normally how a genuinely missing class gets hidden.
# These particular classes are unreachable here: deferred components are
# opt-in, this project declares none in pubspec.yaml, and the manifest's
# application is ${applicationName} - Flutter's ordinary one, not the
# Play-Store split variant. Adding the Play Core library instead would ship
# a dependency purely to satisfy code that never executes.
#
# If deferred components are ever adopted, this block must be replaced with
# the real dependency - the suppression would then be hiding something.
-dontwarn com.google.android.play.core.**

# --- Cashfree payment gateway -----------------------------------------
# The SDK resolves activities and result callbacks by name, and hands
# control to Cashfree's hosted checkout and back. Nothing in this app's
# Java references those entry points, so R8 is entitled to strip them.
#
# The failure mode is the worst one in this file: the build succeeds, the
# app runs, and the checkout screen fails to open - or opens and never
# returns a result - on a real customer trying to pay. Everything else
# about the order still works, so it reads as "payments are flaky" rather
# than as an obfuscation problem.
#
# Note the backend is unaffected either way: it asks Cashfree directly what
# happened, so a broken client cannot mark an order paid. But a customer
# who cannot open checkout cannot pay at all.
-keep class com.cashfree.** { *; }
-keep class com.cashfree.pg.** { *; }
-dontwarn com.cashfree.**

# --- 3D model viewer (WebView) ----------------------------------------
# model_viewer_plus renders Google's <model-viewer> element inside an Android
# WebView, and the bridge between the page and Dart is resolved by NAME at
# runtime - @JavascriptInterface methods have no Java-visible caller, so R8
# is entitled to conclude they are dead and strip them.
#
# The failure mode is the one this whole file exists for: the release build
# compiles, the 3D screen opens, and the model never appears - with nothing
# in the log that points at obfuscation.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class io.flutter.plugins.webviewflutter.** { *; }
-dontwarn android.webkit.**

# --- Keep annotations and generic signatures --------------------------
# json_serializable emits plain constructors rather than reflection, so the
# models themselves are safe; these keep the metadata that plugins and
# Firebase read.
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod

# --- Do not strip line numbers ----------------------------------------
# Obfuscated stack traces with no line numbers are close to unreadable, and
# a crash report that cannot be located is not worth collecting.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
