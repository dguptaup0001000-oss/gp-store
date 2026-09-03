import 'dart:io' show Platform;

import 'package:flutter/foundation.dart';

import '../api/api_client.dart';
import '../logging/app_log.dart';
import 'crash_reporter.dart';

/// Sends a crash to the shop's own backend instead of to a vendor.
///
/// WHY NOT CRASHLYTICS HERE. The worker APK ships without Firebase on
/// purpose, so the rider's install stays small - see worker_main.dart. That
/// decision left its crash handlers wired to a NoOpCrashReporter, which meant
/// every crash on the one app that runs all day, on the cheapest phone in the
/// shop, was caught and dropped. The backend is already there and the worker
/// is already authenticated against it for the whole shift, so posting the
/// crash costs no APK size, no vendor and no money.
///
/// THREE THINGS THIS MUST NEVER DO, because it runs at the worst moment:
///
///   1. Throw. It is called FROM the crash handlers; an exception here
///      replaces a reported crash with an unreported one.
///   2. Block. recordFatal is called on a dying isolate and cannot be
///      awaited, so the post is fire-and-forget.
///   3. Report its own failure. A failed post that reported itself would
///      loop for as long as the network was down.
class BackendCrashReporter implements CrashReporter {
  BackendCrashReporter({String? buildSha}) : _buildSha = buildSha;

  static const _path = '/api/client/crash-reports';

  /// Startup crashes are the ones worth having, and they happen before the
  /// widget tree exists to hand us a client. A short queue holds them until
  /// one arrives; anything past this is a crash loop, and the backend caps
  /// those anyway.
  static const _maxPending = 5;

  final String? _buildSha;

  ApiClient? _client;
  final List<Map<String, dynamic>> _pending = [];
  bool _enabled = true;

  /// True while a post is in flight, so a crash storm cannot open one
  /// request per frame.
  bool _sending = false;

  /// Called once the app has a client. Anything buffered goes out now.
  void attach(ApiClient client) {
    _client = client;
    _drain();
  }

  @override
  Future<void> setEnabled(bool enabled) async {
    _enabled = enabled;
  }

  @override
  void recordFlutterError(FlutterErrorDetails details) {
    // Not fatal: the framework caught it and the app is still running with a
    // broken widget. Worth knowing, and a different severity from a crash.
    _enqueue(
      message: details.exceptionAsString(),
      stack: details.stack?.toString(),
      fatal: false,
    );
  }

  @override
  void recordFatal(Object error, StackTrace? stack) {
    _enqueue(message: error.toString(), stack: stack?.toString(), fatal: true);
  }

  void _enqueue({
    required String message,
    String? stack,
    required bool fatal,
  }) {
    if (!_enabled) return;
    if (_pending.length >= _maxPending) return;

    _pending.add({
      'message': _clip(message, 2000),
      // Clipped on this side too. The backend truncates again - it must,
      // since it cannot trust a phone - but sending 200KB of stack over a
      // rider's mobile data to have it thrown away is its own small cruelty.
      if (stack != null) 'stack': _clip(stack, 8000),
      'fatal': fatal,
      // NO app FIELD, and no reporter id. The backend derives both from the
      // token (see CrashReportService); sending them would be sending
      // something it is right to ignore.
      if (_buildSha != null) 'buildSha': _buildSha,
      'platform': _platformName(),
    });

    _drain();
  }

  void _drain() {
    final client = _client;
    if (client == null || _sending || _pending.isEmpty) return;

    final next = _pending.removeAt(0);
    _sending = true;

    // Deliberately not awaited: recordFatal is called on an isolate that is
    // already going down, and there is nothing to wait for.
    client.dio.post(_path, data: next).then((_) {
      _sending = false;
      _drain();
    }).catchError((Object error) {
      // SWALLOWED ON PURPOSE, and not reported. The rider is offline, or
      // signed out, or the backend is down - none of which this app can fix,
      // and all of which would loop if a failed report were itself a
      // reportable error. One local log line, and the crash is lost.
      _sending = false;
      appLog('Crash report could not be sent: $error');
      return null;
    });
  }

  static String _clip(String value, int max) =>
      value.length <= max ? value : value.substring(0, max);

  static String _platformName() {
    try {
      return Platform.operatingSystem;
    } catch (_) {
      // Platform throws on web and under some test bindings.
      return 'unknown';
    }
  }
}
