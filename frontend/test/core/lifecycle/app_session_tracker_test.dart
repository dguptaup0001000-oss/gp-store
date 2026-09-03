import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/core/lifecycle/app_session_tracker.dart';

/// What the app is allowed to say about how long somebody used it.
///
/// This is collected personal data - declared to Play as App interactions in
/// docs/PLAY_STORE_DECLARATIONS.md sec. 8 - so the shape of it is worth
/// pinning down. Every test here is about what does NOT get reported.
void main() {
  late List<int> reported;
  late DateTime now;

  /// The clock is driven by hand rather than by tester.pump, because what is
  /// being measured is wall time - how long a person held the app open - and
  /// tester.pump only advances the framework's own fake clock.
  void advance(Duration by) => now = now.add(by);

  Future<void> pump(
    WidgetTester tester, {
    Duration minimumSession = Duration.zero,
    bool track = true,
  }) async {
    reported = <int>[];
    now = DateTime(2026, 1, 1, 9);
    await tester.pumpWidget(
      ProviderScope(
        child: AppSessionTracker(
          minimumSession: minimumSession,
          clock: () => now,
          onSessionEnded: track ? (ref, seconds) => reported.add(seconds) : null,
          child: const MaterialApp(home: SizedBox.shrink()),
        ),
      ),
    );
    await tester.pump();
  }

  void background(WidgetTester tester) =>
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.paused);

  void resume(WidgetTester tester) =>
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);

  group('what gets reported', () {
    testWidgets('backgrounding after a real stretch reports once', (tester) async {
      await pump(tester);

      advance(const Duration(seconds: 30));
      background(tester);
      await tester.pump();

      expect(reported, hasLength(1), reason: 'one foreground stretch, one report');
      expect(reported.single, 30);
    });

    testWidgets('a bounce below the minimum is not a visit', (tester) async {
      // A mis-tap, or the app briefly on screen during a task switch.
      // Reporting these would inflate the session count with visits that
      // never happened.
      await pump(tester, minimumSession: const Duration(seconds: 30));

      advance(const Duration(seconds: 1));
      background(tester);
      await tester.pump();

      expect(reported, isEmpty);
    });

    testWidgets('an inactive blip is not the end of a session', (tester) async {
      // inactive fires for a notification shade or an incoming call. Ending
      // a session on each of those would turn one visit into a dozen and
      // make the session count meaningless.
      await pump(tester);

      advance(const Duration(seconds: 10));
      tester.binding
          .handleAppLifecycleStateChanged(AppLifecycleState.inactive);
      await tester.pump();

      expect(reported, isEmpty);
    });

    testWidgets('background then resume then background reports twice', (tester) async {
      await pump(tester);

      advance(const Duration(seconds: 5));
      background(tester);
      await tester.pump();
      resume(tester);
      advance(const Duration(seconds: 5));
      background(tester);
      await tester.pump();

      expect(reported, [5, 5]);
    });

    testWidgets('backgrounding twice in a row reports only the first', (tester) async {
      // Android can deliver paused more than once. The second one is not a
      // second session, and reporting it would double-count the time.
      await pump(tester);

      advance(const Duration(seconds: 5));
      background(tester);
      await tester.pump();
      advance(const Duration(seconds: 5));
      background(tester);
      await tester.pump();

      expect(reported, [5], reason: 'the second paused is not a second session');
    });

    testWidgets('nothing at all is timed when no handler is given', (tester) async {
      // The admin and worker apps pass null. Staff phones are never timed.
      await pump(tester, track: false);

      advance(const Duration(seconds: 60));
      background(tester);
      await tester.pump();

      expect(reported, isEmpty);
    });
  });
}
