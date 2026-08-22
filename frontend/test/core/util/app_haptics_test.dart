import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/core/util/app_haptics.dart';

/// The haptic contract.
///
/// The interesting assertion here is the counting one. "Does it buzz" is easy
/// and rarely wrong; "does it buzz exactly ONCE per tap" is the thing that
/// actually breaks, because a wrapper fires and then the widget it wraps fires
/// too, and one tap ends up feeling like a stuck button rather than a
/// responsive one.
void main() {
  setUp(AppHaptics.resetForTest);

  group('AppHaptics', () {
    test('each call registers exactly once', () {
      AppHaptics.selection();
      expect(AppHaptics.callCount, 1);

      AppHaptics.action();
      expect(AppHaptics.callCount, 2);

      AppHaptics.heavy();
      expect(AppHaptics.callCount, 3);
    });

    test('a disabled haptic still counts, so tests can assert intent without a motor', () {
      AppHaptics.enabled = false;

      AppHaptics.action();

      expect(AppHaptics.callCount, 1,
          reason: 'the call must still be observable - a test runner has no vibration hardware, '
              'and asserting on intent is the only thing available');
    });

    test('the three intensities are distinct entry points, not aliases', () {
      // If these ever collapse into one another the "rule" in the doc comment
      // stops being enforceable and every call site is free to reinvent it.
      expect(AppHaptics.selection, isNot(same(AppHaptics.action)));
      expect(AppHaptics.action, isNot(same(AppHaptics.heavy)));
      expect(AppHaptics.selection, isNot(same(AppHaptics.heavy)));
    });

    test('resetForTest clears state so one test cannot leak into the next', () {
      AppHaptics.selection();
      AppHaptics.enabled = false;

      AppHaptics.resetForTest();

      expect(AppHaptics.callCount, 0);
      expect(AppHaptics.enabled, isTrue);
    });
  });
}
