import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

/// Every buzz in the app goes through AppHaptics.
///
/// WHAT WENT WRONG BEFORE, TWICE. AppHaptics was written because "haptics were
/// already called in about twenty places, each picking an intensity by hand".
/// By the time this test was added there were THIRTY such places again, added
/// one at a time by people who reached for the obvious API.
///
/// THE COST IS NOT TIDINESS. A raw HapticFeedback call:
///
///   - ignores AppHaptics.enabled, so it reaches the platform channel during
///     widget tests, on a runner with no vibration hardware;
///   - is invisible to AppHaptics.callCount, so "one tap produces exactly one
///     haptic" cannot see it - and a wrapper that buzzes over a handler that
///     also buzzes is precisely the bug that assertion exists to catch;
///   - picks its own intensity, which is how the app came to feel different
///     from screen to screen in the first place.
///
/// The mapping is exact, so there is never a reason to reach past it:
/// selectionClick is AppHaptics.selection, lightImpact is AppHaptics.action,
/// mediumImpact is AppHaptics.heavy.
void main() {
  test('nothing calls HapticFeedback directly except AppHaptics', () {
    final offenders = <String>[];

    void walk(Directory dir) {
      for (final entity in dir.listSync()) {
        if (entity is Directory) {
          walk(entity);
          continue;
        }
        if (entity is! File || !entity.path.endsWith('.dart')) continue;
        // The one file allowed to know the platform API exists.
        if (entity.path.endsWith('core/util/app_haptics.dart')) continue;

        final lines = entity.readAsLinesSync();
        for (var i = 0; i < lines.length; i++) {
          // The platform class, not this app's AppHapticFeedback enum - hence
          // the word boundary, which is what stops this test flagging the
          // wrapper file's own vocabulary.
          if (RegExp(r'(?<!App)HapticFeedback\.\w+\(').hasMatch(lines[i])) {
            offenders.add('${entity.path}:${i + 1}: ${lines[i].trim()}');
          }
        }
      }
    }

    walk(Directory('lib'));

    expect(
      offenders,
      isEmpty,
      reason: 'These bypass AppHaptics, so they fire in tests, are invisible '
          'to the one-tap-one-haptic assertion, and choose their own '
          'intensity. Use AppHaptics.selection() / .action() / .heavy() - the '
          'mapping is exact:\n${offenders.join('\n')}',
    );
  });

  test('the guard can actually see a bypass', () {
    // A GUARD ON THE GUARD. The check above passes trivially if the pattern
    // stops matching, and a test that cannot fail is worse than no test.
    final pattern = RegExp(r'(?<!App)HapticFeedback\.\w+\(');

    expect(pattern.hasMatch('    HapticFeedback.mediumImpact();'), isTrue);
    expect(pattern.hasMatch('      HapticFeedback.selectionClick();'), isTrue);
    // And does not fire on this app's own enum, which shares the suffix.
    expect(pattern.hasMatch('  this.feedback = AppHapticFeedback.tap,'), isFalse);
  });
}
