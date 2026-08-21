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
