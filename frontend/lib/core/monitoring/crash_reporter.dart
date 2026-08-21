import 'dart:ui';

import 'package:firebase_crashlytics/firebase_crashlytics.dart';
import 'package:flutter/foundation.dart';

/// Where a crash goes once something has caught it.
///
/// An interface rather than calling Crashlytics directly, for the same
/// reason SpeechEngine exists: the handler wiring below is the part with
/// actual logic in it - which errors are fatal, whether the previous handler
/// still runs, whether the app is told the error was handled - and none of
/// that is testable against a plugin that needs a real Firebase app and a
/// platform channel.
abstract class CrashReporter {
  /// Whether anything is sent at all.
  Future<void> setEnabled(bool enabled);

  /// A framework error: a failed build, layout or paint.
  void recordFlutterError(FlutterErrorDetails details);

  /// An error that escaped every catch in the app.
  void recordFatal(Object error, StackTrace? stack);
}

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

/// Installs the two handlers that decide whether a release crash is ever
/// seen by anybody.
///
/// THERE WAS NO CRASH REPORTING AT ALL. No FlutterError.onError, no
/// PlatformDispatcher.onError, no vendor SDK. In debug that is invisible,
/// because a developer gets the red screen and the console. In release it
/// means a framework error paints a grey box on a customer's phone and
/// nothing anywhere ever records that it happened - the only signal is
/// somebody telling the shop the app "went blank".
///
/// TWO HANDLERS, BECAUSE THEY CATCH DIFFERENT THINGS. FlutterError.onError
/// sees errors thrown inside the framework's own build/layout/paint calls.
/// PlatformDispatcher.instance.onError sees what the framework never does -
/// an uncaught async error, a Future that failed with nothing awaiting it.
/// Either one alone leaves half the failures unrecorded.
///
/// FATAL VS NOT, and this is a deliberate departure from the snippet in
/// Firebase's own docs, which marks both fatal. FlutterError.onError also
/// fires for "A RenderFlex overflowed by 12 pixels" - a real bug, but the
/// app keeps running and the customer keeps shopping. Recording those as
/// crashes would let one recurring overflow sink the crash-free-users rate
/// and bury the failures that actually stop someone checking out. Framework
/// errors are recorded as non-fatal; only what escaped every catch in the
/// app is a crash.
///
/// PRIVACY. Nothing is attached to a report beyond the error and its stack:
/// no user identifier, no custom keys, no request or response bodies. There
/// is no call here that could carry a password, an OTP, a token or a
/// customer's details, and that is a property of the signature rather than
/// a rule someone has to remember.
///
/// Returns without installing anything if [reporter] cannot be enabled -
/// crash reporting failing must never be the reason an app will not start.
Future<void> installCrashHandlers(
  CrashReporter reporter, {
  bool inDebugMode = kDebugMode,
}) async {
  try {
    // Off in debug. A developer already has the console and the red screen,
    // and mixing every hot-reload mistake into the stream the shop's real
    // crashes land in is how that stream stops being read.
    await reporter.setEnabled(!inDebugMode);
  } catch (e) {
    debugPrint('Crash reporting could not be enabled, continuing without it: $e');
    return;
  }

  // Chained, not replaced. The default handler is presentError - dropping it
  // would take the red screen and the console dump with it, which are the
  // two things that make a bug findable while it is still on a desk.
  final previousOnError = FlutterError.onError;
  FlutterError.onError = (details) {
    previousOnError?.call(details);
    reporter.recordFlutterError(details);
  };

  PlatformDispatcher.instance.onError = (error, stack) {
    reporter.recordFatal(error, stack);
    // true: reported, so the platform should not also print it as unhandled.
    return true;
  };
}
