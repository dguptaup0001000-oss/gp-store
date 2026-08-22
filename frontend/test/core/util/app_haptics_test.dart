import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/core/util/app_haptics.dart';

/// The haptic contract.
///
/// Asserted against the real platform channel rather than against the tear-offs
/// themselves. Comparing AppHaptics.selection to AppHaptics.action proves only
/// that two functions are different objects - it would still pass if every one
/// of them fired the same buzz. What matters is the payload that reaches the
/// platform, so that is what these read.
///
/// The counting assertions are the other half. "Does it buzz" is easy and
/// rarely wrong; "does it buzz exactly ONCE per tap" is the thing that actually
/// breaks, because a wrapper fires and then the widget it wraps fires too, and
/// one tap ends up feeling like a stuck button rather than a responsive one.
void main() {
  // Required before touching SystemChannels: a haptic is a platform message,
  // and without a binding there is no messenger to send it through.
  TestWidgetsFlutterBinding.ensureInitialized();

  late List<String> vibrations;

  setUp(() {
    AppHaptics.resetForTest();
    vibrations = <String>[];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(SystemChannels.platform, (call) async {
      if (call.method == 'HapticFeedback.vibrate') {
        vibrations.add(call.arguments as String? ?? 'default');
      }
      return null;
    });
  });

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(SystemChannels.platform, null);
  });

  /// The haptic is deliberately not awaited in production - see AppHaptics -
  /// so the platform message is still in the microtask queue when the call
  /// returns. This lets it land before the assertion reads it.
  Future<void> settle() => Future<void>.delayed(Duration.zero);

  group('AppHaptics', () {
    test('the three intensities reach the platform as three different buzzes', () async {
      AppHaptics.selection();
      AppHaptics.action();
      AppHaptics.heavy();
      await settle();

      expect(vibrations, [
        'HapticFeedbackType.selectionClick',
        'HapticFeedbackType.lightImpact',
        'HapticFeedbackType.mediumImpact',
      ]);
    });

    test('one call is one buzz', () async {
      AppHaptics.selection();
      await settle();

      expect(vibrations, hasLength(1),
          reason: 'a tap that buzzes twice feels like a stuck button');
      expect(AppHaptics.callCount, 1);
    });

    test('each call registers exactly once', () async {
      AppHaptics.selection();
      expect(AppHaptics.callCount, 1);

      AppHaptics.action();
      expect(AppHaptics.callCount, 2);

      AppHaptics.heavy();
      expect(AppHaptics.callCount, 3);
      await settle();
    });

    test('disabling stops the buzz but not the count', () async {
      AppHaptics.enabled = false;

      AppHaptics.action();
      await settle();

      expect(vibrations, isEmpty, reason: 'nothing should reach the platform');
      expect(AppHaptics.callCount, 1,
          reason: 'the call must still be observable - a widget test has no vibration '
              'hardware, and asserting on intent is the only thing available');
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
