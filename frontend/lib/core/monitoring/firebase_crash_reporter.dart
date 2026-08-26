import 'package:firebase_crashlytics/firebase_crashlytics.dart';
import 'package:flutter/foundation.dart';

import 'crash_reporter.dart';

/// Crashlytics-backed reporter for the customer app.
///
/// Isolated from [installCrashHandlers] so the worker slim APK can install
/// crash handlers without compiling Firebase.
class FirebaseCrashReporter implements CrashReporter {
  const FirebaseCrashReporter();

  FirebaseCrashlytics get _crashlytics => FirebaseCrashlytics.instance;

  @override
  Future<void> setEnabled(bool enabled) =>
      _crashlytics.setCrashlyticsCollectionEnabled(enabled);

  @override
  void recordFlutterError(FlutterErrorDetails details) =>
      _crashlytics.recordFlutterError(details);

  @override
  void recordFatal(Object error, StackTrace? stack) =>
      _crashlytics.recordError(error, stack, fatal: true);
}
