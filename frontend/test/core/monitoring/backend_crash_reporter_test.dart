import 'package:dio/dio.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/core/monitoring/backend_crash_reporter.dart';

import '../../support/test_api_client.dart';

/// The worker app's crash reporter, which runs at the worst possible moment.
///
/// WHAT IT REPLACED. worker_main.dart installed the crash handlers and handed
/// them a NoOpCrashReporter, because that APK deliberately ships without
/// Firebase to stay small. The handlers ran and threw the crash away, so the
/// one app that runs all day on the cheapest phone in the shop was the only
/// one nobody could get a crash report from.
void main() {
  setUp(setUpFakeSecureStorage);

  /// A fresh adapter and a fresh list, PER TEST, and that is the whole point.
  ///
  /// These started as two shared variables reset in setUp, and CI caught what
  /// that hides: the reporter drains its queue asynchronously, so posts still
  /// in flight when a test ends land in whatever list the closure points at
  /// next - which was the following test's. The crash-loop test above leaked
  /// into the truncation test below it, which then failed on `single` with
  /// "Too many elements" while the code under test was perfectly correct.
  ///
  /// Giving each test its own pair makes that impossible rather than
  /// unlikely: a late arrival lands in a list nobody reads again.
  ({FakeHttpClientAdapter adapter, List<Map<String, dynamic>> posted})
      accepting() {
    final posted = <Map<String, dynamic>>[];
    final adapter = FakeHttpClientAdapter();
    adapter.on('POST', '/api/client/crash-reports', (options) {
      posted.add(Map<String, dynamic>.from(options.data as Map));
      return const FakeResponse({}, statusCode: 202);
    });
    return (adapter: adapter, posted: posted);
  }

  test('a crash before the client exists is not lost', () async {
    final t = accepting();
    final reporter = BackendCrashReporter(buildSha: 'abc1234');

    // THE STARTUP CRASH, which is the one worth having and the one that
    // happens before there is a widget tree to hand over a client.
    reporter.recordFatal(StateError('died on startup'), StackTrace.current);
    expect(t.posted, isEmpty, reason: 'nothing can be sent with no client yet');

    reporter.attach(buildTestApiClient(t.adapter));
    await pumpEventQueue();

    expect(t.posted, hasLength(1));
    expect(t.posted.single['message'], contains('died on startup'));
    expect(t.posted.single['fatal'], isTrue);
    expect(t.posted.single['buildSha'], 'abc1234');
  });

  test('it never says who is reporting - the token does', () async {
    final t = accepting();
    final reporter =
        BackendCrashReporter()..attach(buildTestApiClient(t.adapter));

    reporter.recordFatal(Exception('boom'), StackTrace.current);
    await pumpEventQueue();

    // The backend derives the app and the reporter from the token. Sending
    // any of these would be sending something it is right to ignore - and a
    // field the client fills in is a field somebody can forge.
    expect(t.posted.single.containsKey('app'), isFalse);
    expect(t.posted.single.containsKey('customerId'), isFalse);
    expect(t.posted.single.containsKey('workerId'), isFalse);
  });

  test('a framework error is reported, and not as fatal', () async {
    final t = accepting();
    final reporter =
        BackendCrashReporter()..attach(buildTestApiClient(t.adapter));

    reporter.recordFlutterError(FlutterErrorDetails(
      exception: Exception('bad build'),
      stack: StackTrace.current,
    ));
    await pumpEventQueue();

    expect(t.posted.single['fatal'], isFalse,
        reason: 'the framework caught it and the app kept running');
  });

  test('a failed post is swallowed, never re-reported', () async {
    final failing = FakeHttpClientAdapter();
    failing.on('POST', '/api/client/crash-reports', (options) {
      throw DioException(requestOptions: options, error: 'offline');
    });

    final reporter = BackendCrashReporter()..attach(buildTestApiClient(failing));

    // A rider in a basement. The report is lost, which is correct - what
    // must NOT happen is this throwing, because it is called FROM the crash
    // handlers, or reporting its own failure, which would loop for as long
    // as the network was down.
    expect(
      () => reporter.recordFatal(Exception('boom'), StackTrace.current),
      returnsNormally,
    );
    await pumpEventQueue();
  });

  test('a crash loop cannot queue without limit', () async {
    final t = accepting();
    final reporter = BackendCrashReporter();

    // No client attached, so nothing drains and the queue is all there is.
    for (var i = 0; i < 50; i++) {
      reporter.recordFatal(Exception('loop $i'), StackTrace.current);
    }

    reporter.attach(buildTestApiClient(t.adapter));
    await pumpEventQueue();

    expect(t.posted.length, lessThanOrEqualTo(5),
        reason: 'an app dying every frame must not buffer without bound');
  });

  test('an enormous stack is clipped before it goes near mobile data',
      () async {
    final t = accepting();
    final reporter =
        BackendCrashReporter()..attach(buildTestApiClient(t.adapter));

    reporter.recordFatal(Exception('deep'), StackTrace.fromString('x' * 50000));
    await pumpEventQueue();

    expect((t.posted.single['stack'] as String).length, lessThanOrEqualTo(8000));
  });

  test('reporting can be turned off', () async {
    final t = accepting();
    final reporter =
        BackendCrashReporter()..attach(buildTestApiClient(t.adapter));
    await reporter.setEnabled(false);

    reporter.recordFatal(Exception('boom'), StackTrace.current);
    await pumpEventQueue();

    expect(t.posted, isEmpty);
  });
}
