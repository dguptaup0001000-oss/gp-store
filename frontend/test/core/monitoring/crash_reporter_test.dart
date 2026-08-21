import 'dart:ui';

import 'package:flutter/foundation.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/core/monitoring/crash_reporter.dart';

class FakeCrashReporter implements CrashReporter {
  bool? enabled;
  bool failToEnable = false;

  final List<FlutterErrorDetails> flutterErrors = [];
  final List<Object> fatals = [];

  @override
  Future<void> setEnabled(bool value) async {
    if (failToEnable) throw StateError('crashlytics unavailable');
    enabled = value;
  }

  @override
  void recordFlutterError(FlutterErrorDetails details) => flutterErrors.add(details);

  @override
  void recordFatal(Object error, StackTrace? stack) => fatals.add(error);
}

void main() {
  // These handlers are process-global. Captured before anything touches
  // them and put back afterwards, or one test here silently changes how
  // every later test in the run reports its own failures.
  late FlutterExceptionHandler? originalFlutterOnError;
  late ErrorCallback? originalPlatformOnError;
  late FakeCrashReporter reporter;

  setUp(() {
    originalFlutterOnError = FlutterError.onError;
    originalPlatformOnError = PlatformDispatcher.instance.onError;
    reporter = FakeCrashReporter();
  });

  tearDown(() {
    FlutterError.onError = originalFlutterOnError;
    PlatformDispatcher.instance.onError = originalPlatformOnError;
  });

  FlutterErrorDetails detailsFor(Object error) =>
      FlutterErrorDetails(exception: error, library: 'test');

  group('what gets recorded', () {
    test('a framework error reaches the reporter', () async {
      await installCrashHandlers(reporter, inDebugMode: false);

      FlutterError.onError!(detailsFor(StateError('build failed')));

      expect(reporter.flutterErrors, hasLength(1));
      expect(reporter.flutterErrors.single.exception, isStateError);
    });

    test('an error that escaped every catch is recorded as fatal', () async {
      await installCrashHandlers(reporter, inDebugMode: false);

      final handled = PlatformDispatcher.instance.onError!(
        StateError('nothing awaited this'),
        StackTrace.current,
      );

      expect(reporter.fatals, hasLength(1));
      expect(handled, isTrue, reason: 'reported, so the platform should not also print it');
    });

    test('a framework error is NOT recorded as fatal', () async {
      // "A RenderFlex overflowed by 12 pixels" comes through
      // FlutterError.onError. It is a real bug and the customer keeps
      // shopping; counting it as a crash would let one recurring overflow
      // sink the crash-free rate and bury the failures that stop checkout.
      await installCrashHandlers(reporter, inDebugMode: false);

      FlutterError.onError!(detailsFor(FlutterError('A RenderFlex overflowed by 12 pixels')));

      expect(reporter.flutterErrors, hasLength(1));
      expect(reporter.fatals, isEmpty);
    });
  });

  group('what installing must not break', () {
    test('the previous handler still runs', () async {
      // presentError is the default, and it is what puts the red screen on
      // a developer's simulator and the dump in the console. Replacing it
      // rather than chaining would trade a findable bug for a silent one.
      final seenByPrevious = <Object>[];
      FlutterError.onError = (details) => seenByPrevious.add(details.exception);

      await installCrashHandlers(reporter, inDebugMode: false);
      final error = StateError('build failed');
      FlutterError.onError!(detailsFor(error));

      expect(seenByPrevious, [error]);
      expect(reporter.flutterErrors, hasLength(1));
    });

    test('a reporter that cannot start leaves the handlers untouched', () async {
      // Crash reporting failing is never a reason for the app not to run,
      // and half-installed handlers pointing at a dead reporter would be
      // worse than none.
      reporter.failToEnable = true;
      final before = FlutterError.onError;

      await installCrashHandlers(reporter, inDebugMode: false);

      expect(FlutterError.onError, same(before));
    });
  });

  group('collection', () {
    test('is on in release', () async {
      await installCrashHandlers(reporter, inDebugMode: false);
      expect(reporter.enabled, isTrue);
    });

    test('is off in debug', () async {
      // A developer already has the console and the red screen. Mixing
      // every hot-reload mistake into the stream the shop's real crashes
      // land in is how that stream stops being read.
      await installCrashHandlers(reporter, inDebugMode: true);
      expect(reporter.enabled, isFalse);
    });
  });
}
